package com.appsmith.server.services.ce;

import com.appsmith.external.helpers.AppsmithBeanUtils;
import com.appsmith.external.helpers.MustacheHelper;
import com.appsmith.external.models.Datasource;
import com.appsmith.external.models.DatasourceConfiguration;
import com.appsmith.external.models.DatasourceTestResult;
import com.appsmith.external.models.Endpoint;
import com.appsmith.external.models.Policy;
import com.appsmith.external.plugins.PluginExecutor;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.acl.PolicyGenerator;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.Workspace;
import com.appsmith.server.domains.Plugin;
import com.appsmith.server.domains.User;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.PluginExecutorHelper;
import com.appsmith.server.repositories.DatasourceRepository;
import com.appsmith.server.repositories.NewActionRepository;
import com.appsmith.server.services.AnalyticsService;
import com.appsmith.server.services.BaseService;
import com.appsmith.server.services.WorkspaceService;
import com.appsmith.server.services.PluginService;
import com.appsmith.server.services.SequenceService;
import com.appsmith.server.services.SessionUserService;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.util.function.Tuple2;

import javax.validation.Validator;
import javax.validation.constraints.NotNull;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.appsmith.external.helpers.AppsmithBeanUtils.copyNestedNonNullProperties;
import static com.appsmith.server.acl.AclPermission.MANAGE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_MANAGE_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.WORKSPACE_READ_APPLICATIONS;

@Slf4j
public class DatasourceServiceCEImpl extends BaseService<DatasourceRepository, Datasource, String> implements DatasourceServiceCE {

    private final WorkspaceService workspaceService;
    private final SessionUserService sessionUserService;
    private final PluginService pluginService;
    private final PluginExecutorHelper pluginExecutorHelper;
    private final PolicyGenerator policyGenerator;
    private final SequenceService sequenceService;
    private final NewActionRepository newActionRepository;


    @Autowired
    public DatasourceServiceCEImpl(Scheduler scheduler,
                                   Validator validator,
                                   MongoConverter mongoConverter,
                                   ReactiveMongoTemplate reactiveMongoTemplate,
                                   DatasourceRepository repository,
                                   WorkspaceService workspaceService,
                                   AnalyticsService analyticsService,
                                   SessionUserService sessionUserService,
                                   PluginService pluginService,
                                   PluginExecutorHelper pluginExecutorHelper,
                                   PolicyGenerator policyGenerator,
                                   SequenceService sequenceService,
                                   NewActionRepository newActionRepository) {

        super(scheduler, validator, mongoConverter, reactiveMongoTemplate, repository, analyticsService);
        this.workspaceService = workspaceService;
        this.sessionUserService = sessionUserService;
        this.pluginService = pluginService;
        this.pluginExecutorHelper = pluginExecutorHelper;
        this.policyGenerator = policyGenerator;
        this.sequenceService = sequenceService;
        this.newActionRepository = newActionRepository;
    }

    @Override
    public Mono<Datasource> create(@NotNull Datasource datasource) {
        String workspaceId = datasource.getWorkspaceId();
        if (workspaceId == null) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.WORKSPACE_ID));
        }
        if (datasource.getId() != null) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.ID));
        }
        if (!StringUtils.hasLength(datasource.getGitSyncId())) {
            datasource.setGitSyncId(datasource.getWorkspaceId() + "_" + new ObjectId());
        }

        Mono<Datasource> datasourceMono = Mono.just(datasource);
        if (!StringUtils.hasLength(datasource.getName())) {
            datasourceMono = sequenceService
                    .getNextAsSuffix(Datasource.class, " for workspace with _id : " + workspaceId)
                    .zipWith(datasourceMono, (sequenceNumber, datasource1) -> {
                        datasource1.setName(Datasource.DEFAULT_NAME_PREFIX + sequenceNumber);
                        return datasource1;
                    });
        }

        Mono<Datasource> datasourceWithPoliciesMono = datasourceMono
                .flatMap(datasource1 -> {
                    Mono<User> userMono = sessionUserService.getCurrentUser();
                    return generateAndSetDatasourcePolicies(userMono, datasource1);
                });

        return datasourceWithPoliciesMono
                .flatMap(this::validateAndSaveDatasourceToRepository)
                .flatMap(savedDatasource ->
                    analyticsService.sendCreateEvent(savedDatasource, getAnalyticsProperties(savedDatasource))
                )
                .flatMap(this::populateHintMessages); // For REST API datasource create flow.
    }

    private Mono<Datasource> generateAndSetDatasourcePolicies(Mono<User> userMono, Datasource datasource) {
        return userMono
                .flatMap(user -> {
                    Mono<Workspace> orgMono = workspaceService.findById(datasource.getWorkspaceId(), WORKSPACE_MANAGE_APPLICATIONS)
                            .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.WORKSPACE, datasource.getWorkspaceId())));

                    return orgMono.map(org -> {
                        Set<Policy> policySet = org.getPolicies().stream()
                                .filter(policy ->
                                        policy.getPermission().equals(WORKSPACE_MANAGE_APPLICATIONS.getValue()) ||
                                                policy.getPermission().equals(WORKSPACE_READ_APPLICATIONS.getValue())
                                ).collect(Collectors.toSet());

                        Set<Policy> documentPolicies = policyGenerator.getAllChildPolicies(policySet, Workspace.class, Datasource.class);
                        datasource.setPolicies(documentPolicies);
                        return datasource;
                    });
                });
    }

    public Mono<Datasource> populateHintMessages(Datasource datasource) {

        if(datasource == null) {
            /*
             * - Not throwing an exception here because we do not throw an error in case of missing datasource.
             *   We try not to fail as much as possible during create and update actions.
             */
            return Mono.just(new Datasource());
        }

        if(datasource.getPluginId() == null) {
            /*
             * - Not throwing an exception here because we try not to fail as much as possible during datasource create
             * and update events.
             */
            return Mono.just(datasource);
        }

        final Mono<Plugin> pluginMono = pluginService.findById(datasource.getPluginId());
        Mono<PluginExecutor> pluginExecutorMono = pluginExecutorHelper.getPluginExecutor(pluginMono)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.PLUGIN,
                        datasource.getPluginId())));

        /**
         * Delegate the task of generating hint messages to the concerned plugin, since only the
         * concerned plugin can correctly interpret their configuration.
         */
        return pluginExecutorMono
                .flatMap(pluginExecutor -> pluginExecutor.getHintMessages(null,
                        datasource.getDatasourceConfiguration()))
                .flatMap(tuple -> {
                    Set datasourceHintMessages = ((Tuple2<Set, Set>) tuple).getT1();
                    datasource.getMessages().addAll(datasourceHintMessages);
                    return Mono.just(datasource);
                });
    }

    @Override
    public Mono<Datasource> update(String id, Datasource datasource) {
        if (id == null) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.ID));
        }

        // Since policies are a server only concept, first set the empty set (set by constructor) to null
        datasource.setPolicies(null);

        Mono<Datasource> datasourceMono = repository.findById(id)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.DATASOURCE, id)));
        return datasourceMono
                .map(dbDatasource -> {
                    copyNestedNonNullProperties(datasource, dbDatasource);
                    if (datasource.getDatasourceConfiguration() != null && datasource.getDatasourceConfiguration().getAuthentication() == null) {
                        if (dbDatasource.getDatasourceConfiguration() != null) {
                            dbDatasource.getDatasourceConfiguration().setAuthentication(null);
                        }
                    }
                    return dbDatasource;
                })
                .flatMap(this::validateAndSaveDatasourceToRepository)
                .flatMap(savedDatasource ->
                        analyticsService.sendUpdateEvent(savedDatasource, getAnalyticsProperties(savedDatasource))
                )
                .flatMap(this::populateHintMessages);
    }

    @Override
    public Mono<Datasource> validateDatasource(Datasource datasource) {
        Set<String> invalids = new HashSet<>();
        datasource.setInvalids(invalids);

        if (!StringUtils.hasText(datasource.getName())) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.NAME));
        }

        if (datasource.getPluginId() == null) {
            invalids.add(AppsmithError.PLUGIN_ID_NOT_GIVEN.getMessage());
            return Mono.just(datasource);
        }

        if (datasource.getWorkspaceId() == null) {
            invalids.add(AppsmithError.WORKSPACE_ID_NOT_GIVEN.getMessage());
            return Mono.just(datasource);
        }

        Mono<Workspace> checkPluginInstallationAndThenReturnWorkspaceMono = workspaceService
                .findByIdAndPluginsPluginId(datasource.getWorkspaceId(), datasource.getPluginId())
                .switchIfEmpty(Mono.defer(() -> {
                    invalids.add(AppsmithError.PLUGIN_NOT_INSTALLED.getMessage(datasource.getPluginId()));
                    return Mono.just(new Workspace());
                }));

        if (datasource.getDatasourceConfiguration() == null) {
            invalids.add(AppsmithError.NO_CONFIGURATION_FOUND_IN_DATASOURCE.getMessage());
        }

        final Mono<Plugin> pluginMono = pluginService.findById(datasource.getPluginId()).cache();
        Mono<PluginExecutor> pluginExecutorMono = pluginExecutorHelper.getPluginExecutor(pluginMono)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.PLUGIN, datasource.getPluginId())));

        return checkPluginInstallationAndThenReturnWorkspaceMono
                .then(pluginExecutorMono)
                .flatMap(pluginExecutor -> {
                    DatasourceConfiguration datasourceConfiguration = datasource.getDatasourceConfiguration();
                    if (datasourceConfiguration != null && !pluginExecutor.isDatasourceValid(datasourceConfiguration)) {
                        invalids.addAll(pluginExecutor.validateDatasource(datasourceConfiguration));
                    }

                    return pluginMono.map(plugin -> {
                        // setting the plugin name to datasource.
                        // this is required in analytics events for datasource e.g. create ds, update ds
                        datasource.setPluginName(plugin.getName());
                        return datasource;
                    });
                });
    }

    @Override
    public Mono<Datasource> save(Datasource datasource) {
        if (datasource.getGitSyncId() == null) {
            datasource.setGitSyncId(datasource.getWorkspaceId() + "_" + Instant.now().toString());
        }
        return repository.save(datasource);
    }

    private Datasource sanitizeDatasource(Datasource datasource) {
        if (datasource.getDatasourceConfiguration() != null
                && !CollectionUtils.isEmpty(datasource.getDatasourceConfiguration().getEndpoints())) {
            for (final Endpoint endpoint : datasource.getDatasourceConfiguration().getEndpoints()) {
                if (endpoint != null && endpoint.getHost() != null) {
                    endpoint.setHost(endpoint.getHost().trim());
                }
            }
        }

        return datasource;
    }

    private Mono<Datasource> validateAndSaveDatasourceToRepository(Datasource datasource) {

        Mono<User> currentUserMono = sessionUserService.getCurrentUser();

        return Mono.just(datasource)
                .map(this::sanitizeDatasource)
                .flatMap(this::validateDatasource)
                .zipWith(currentUserMono)
                .flatMap(tuple -> {
                    Datasource unsavedDatasource = tuple.getT1();
                    User user = tuple.getT2();
                    Datasource userPermissionsInDatasource = repository.setUserPermissionsInObject(unsavedDatasource, user);
                    return repository.save(userPermissionsInDatasource).map(savedDatasource -> {
                        // datasource.pluginName is a transient field. It was set by validateDatasource method
                        // object from db will have pluginName=null so set it manually from the unsaved datasource obj
                        savedDatasource.setPluginName(unsavedDatasource.getPluginName());
                        return savedDatasource;
                    });
                });
    }

    /**
     * This function can now only be used if you send the entire datasource object and not just id inside the datasource object. We only fetch
     * the password from the db if its a saved datasource before testing.
     */
    @Override
    public Mono<DatasourceTestResult> testDatasource(Datasource datasource) {
        Mono<Datasource> datasourceMono = Mono.just(datasource);
        // Fetch any fields that maybe encrypted from the db if the datasource being tested does not have those fields set.
        // This scenario would happen whenever an existing datasource is being tested and no changes are present in the
        // encrypted field (because encrypted fields are not sent over the network after encryption back to the client
        if (datasource.getId() != null && datasource.getDatasourceConfiguration() != null &&
                datasource.getDatasourceConfiguration().getAuthentication() != null) {
            datasourceMono = getById(datasource.getId())
                    .map(datasource1 -> {
                        AppsmithBeanUtils.copyNestedNonNullProperties(datasource, datasource1);
                        return datasource1;
                    })
                    .switchIfEmpty(Mono.just(datasource));
        }

        return datasourceMono
                .flatMap(this::validateDatasource)
                .flatMap(this::populateHintMessages)
                .flatMap(datasource1 -> {
                    Mono<DatasourceTestResult> datasourceTestResultMono;
                    if (CollectionUtils.isEmpty(datasource1.getInvalids())) {
                        datasourceTestResultMono = testDatasourceViaPlugin(datasource1);
                    } else {
                        datasourceTestResultMono = Mono.just(new DatasourceTestResult(datasource1.getInvalids()));
                    }

                    return datasourceTestResultMono
                            .map(datasourceTestResult -> {
                                datasourceTestResult.setMessages(datasource1.getMessages());
                                return datasourceTestResult;
                            });
                });
    }

    private Mono<DatasourceTestResult> testDatasourceViaPlugin(Datasource datasource) {
        Mono<PluginExecutor> pluginExecutorMono = pluginExecutorHelper.getPluginExecutor(pluginService.findById(datasource.getPluginId()))
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.PLUGIN, datasource.getPluginId())));

        return pluginExecutorMono
                .flatMap(pluginExecutor -> pluginExecutor.testDatasource(datasource.getDatasourceConfiguration()));
    }

    @Override
    public Mono<Datasource> findByNameAndWorkspaceId(String name, String workspaceId, AclPermission permission) {
        return repository.findByNameAndWorkspaceId(name, workspaceId, permission);
    }

    @Override
    public Mono<Datasource> findById(String id, AclPermission aclPermission) {
        return repository.findById(id, aclPermission);
    }

    @Override
    public Mono<Datasource> findById(String id) {
        return repository.findById(id);
    }

    @Override
    public Set<String> extractKeysFromDatasource(Datasource datasource) {
        if (datasource == null || datasource.getDatasourceConfiguration() == null) {
            return new HashSet<>();
        }

        return MustacheHelper.extractMustacheKeysFromFields(datasource.getDatasourceConfiguration());
    }

    @Override
    public Flux<Datasource> get(MultiValueMap<String, String> params) {
        /**
         * Note : Currently this API is ONLY used to fetch datasources for a workspace.
         */
        // Remove branch name as datasources are not shared across branches
        params.remove(FieldName.DEFAULT_RESOURCES + "." + FieldName.BRANCH_NAME);
        if (params.getFirst(FieldName.WORKSPACE_ID) != null) {
            return findAllByWorkspaceId(params.getFirst(FieldName.WORKSPACE_ID), AclPermission.READ_DATASOURCES);
        }

        return Flux.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.WORKSPACE_ID));
    }

    @Override
    public Flux<Datasource> findAllByWorkspaceId(String workspaceId, AclPermission permission) {
        return repository.findAllByWorkspaceId(workspaceId, permission)
                .flatMap(this::populateHintMessages);
    }

    @Override
    public Flux<Datasource> saveAll(List<Datasource> datasourceList) {
        datasourceList
                .stream()
                .filter(datasource -> datasource.getGitSyncId() == null)
                .forEach(datasource -> datasource.setGitSyncId(datasource.getWorkspaceId() + "_" + Instant.now().toString()));
        return repository.saveAll(datasourceList);
    }

    @Override
    public Mono<Datasource> archiveById(String id) {
        return repository
                .findById(id, MANAGE_DATASOURCES)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.DATASOURCE, id)))
                .zipWhen(datasource -> newActionRepository.countByDatasourceId(datasource.getId()))
                .flatMap(objects -> {
                    final Long actionsCount = objects.getT2();
                    if (actionsCount > 0) {
                        return Mono.error(new AppsmithException(AppsmithError.DATASOURCE_HAS_ACTIONS, actionsCount));
                    }
                    return Mono.just(objects.getT1());
                })
                .flatMap(toDelete -> repository.archive(toDelete).thenReturn(toDelete))
                .flatMap(analyticsService::sendDeleteEvent);
    }

    @Override
    public Mono<Datasource> archiveByIdAndBranchName(String id, String branchName) {
        // Ignore branchName as datasources are branch independent entity
        return this.archiveById(id);
    }

    private Map<String, Object> getAnalyticsProperties(Datasource datasource) {
        Map<String, Object> analyticsProperties = new HashMap<>();
        analyticsProperties.put("orgId", datasource.getWorkspaceId());
        analyticsProperties.put("pluginName", datasource.getPluginName());
        analyticsProperties.put("dsName", datasource.getName());
        return analyticsProperties;
    }
}
