const commonlocators = require("../../../../locators/commonlocators.json");
const formWidgetsPage = require("../../../../locators/FormWidgets.json");
const publish = require("../../../../locators/publishWidgetspage.json");
const dsl = require("../../../../fixtures/multiSelectDsl.json");
import data from "../../../../fixtures/example.json";
import {
  PROPERTY_SELECTOR,
  WIDGET,
  getWidgetSelector,
} from "../../../../locators/WidgetLocators";
import { ObjectsRegistry } from "../../../../support/Objects/Registry";

/// <reference types="Cypress" />
const agHelper = ObjectsRegistry.AggregateHelper;

describe("MultiSelect Widget Functionality", function() {
  before(() => {
    cy.addDsl(dsl);
  });
  it("Selects value with invalid default value", () => {
    cy.openPropertyPane("multiselectwidgetv2");
    cy.testJsontext("options", JSON.stringify(data.input));
    cy.testJsontext("defaultvalue", "{{ undefined }}");
    cy.get(formWidgetsPage.multiselectwidgetv2)
      .find(".rc-select-selection-search-input")
      .first()
      .focus({ force: true })
      .type("{uparrow}", { force: true });
    cy.get(".multi-select-dropdown")
      .contains("Option 3")
      .click({ force: true });
    cy.wait(2000);
    //Validating option inside multiselect widget
    cy.get(".rc-select-selection-item-content")
      .first()
      .should("have.text", "Option 3");
  });
  it("Selects value with enter in default value", () => {
    cy.testJsontext(
      "defaultvalue",
      '[\n  {\n    "label": "Option 3",\n    "value": "3"\n  }\n]',
    );
    cy.get(formWidgetsPage.multiselectwidgetv2)
      .find(".rc-select-selection-item-content")
      .first()
      .should("have.text", "Option 3");
  });
  it("Dropdown Functionality To Validate Options", function() {
    cy.get(".rc-select-selector").click({ force: true });
    cy.dropdownMultiSelectDynamic("Option 2");
  });
  it("Dropdown Functionality To Unchecked Visible Widget", function() {
    cy.togglebarDisable(commonlocators.visibleCheckbox);
    cy.PublishtheApp();
    cy.get(publish.multiselectwidgetv2 + " " + ".rc-select-selector").should(
      "not.exist",
    );
    cy.get(publish.backToEditor).click();
  });
  it("Dropdown Functionality To Check Visible Widget", function() {
    cy.openPropertyPane("multiselectwidgetv2");
    cy.togglebar(commonlocators.visibleCheckbox);
    cy.PublishtheApp();
    cy.get(publish.multiselectwidgetv2 + " " + ".rc-select-selector").should(
      "be.visible",
    );
    cy.get(publish.backToEditor).click();
  });
  it("Dropdown Functionality To Check Allow select all option", function() {
    // select all option is not enable
    cy.get(formWidgetsPage.multiselectwidgetv2)
      .find(".rc-select-selection-item-content")
      .first()
      .should("not.have.text", "Select all");
    // enable select all option from property pane
    cy.openPropertyPane("multiselectwidgetv2");
    cy.togglebar(commonlocators.allowSelectAllCheckbox);

    // press select all option
    cy.get(formWidgetsPage.multiselectwidgetv2)
      .find(".rc-select-selection-search-input")
      .first()
      .focus({ force: true })
      .type("{uparrow}", { force: true });
    cy.get(".multi-select-dropdown")
      .contains("Select all")
      .click({ force: true });
    cy.wait(2000);
    //Validating option inside multiselect widget
    cy.get(".rc-select-selection-item-content")
      .eq(0)
      .should("have.text", "Option 1");
    cy.get(".rc-select-selection-item-content")
      .eq(1)
      .should("have.text", "Option 2");
  });
  it("Check isDirty meta property", function() {
    cy.openPropertyPane(WIDGET.TEXT);
    cy.updateCodeInput(PROPERTY_SELECTOR.text, `{{MultiSelect2.isDirty}}`);
    // Init isDirty by changing defaultOptionValue
    cy.openPropertyPane("multiselectwidgetv2");
    cy.updateCodeInput(
      PROPERTY_SELECTOR.defaultValue,
      '[\n  {\n    "label": "Option 1",\n    "value": "1"\n  }\n]',
    );
    cy.get(getWidgetSelector(WIDGET.TEXT))
      .eq(0)
      .should("contain", "false");
    // Interact with UI
    cy.get(".rc-select-selector").click({ force: true });
    cy.dropdownMultiSelectDynamic("Option 2");
    // Check if isDirty is set to true
    cy.get(getWidgetSelector(WIDGET.TEXT))
      .eq(0)
      .should("contain", "true");
    // Reset isDirty by changing defaultOptionValue
    cy.updateCodeInput(
      PROPERTY_SELECTOR.defaultValue,
      '[\n  {\n    "label": "Option 2",\n    "value": "2"\n  }\n]',
    );
    // Check if isDirty is set to false
    cy.get(getWidgetSelector(WIDGET.TEXT))
      .eq(0)
      .should("contain", "false");
  });

  it("Verify MultiSelect resets to default value", function() {
    cy.openPropertyPane("multiselectwidgetv2");
    // set options
    cy.testJsontext(
      "options",
      JSON.stringify([
        { label: "RED", value: "RED" },
        { label: "GREEN", value: "GREEN" },
        { label: "YELLOW", value: "YELLOW" },
      ]),
    );
    // set default value
    cy.testJsontext(
      "defaultvalue",
      JSON.stringify([{ label: "RED", value: "RED" }], null, 2),
    );
    // select other options
    agHelper.SelectFromMultiSelect(["GREEN", "YELLOW"]);
    agHelper.RemoveMultiSelectItems(["RED"]);
    // reset multiselect
    cy.get(
      `${getWidgetSelector("buttonwidget")}:contains('Reset MultiSelect')`,
    ).click();

    // verify value is equal to default value
    cy.get(getWidgetSelector("textwidget"))
      .eq(1)
      .should("have.text", "RED");
  });

  it("Verify MultiSelect deselection behavior", function() {
    agHelper.RemoveMultiSelectItems(["RED"]);

    // verify value is equal to default value
    cy.get(getWidgetSelector("textwidget"))
      .eq(1)
      .should("have.text", "");
  });
});
afterEach(() => {
  // put your clean up code if any
});
