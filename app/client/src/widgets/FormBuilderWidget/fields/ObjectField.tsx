import React from "react";
import styled from "styled-components";

import fieldRenderer from "./fieldRenderer";
import { BaseFieldComponentProps } from "./types";

type ObjectFieldProps = BaseFieldComponentProps;

const WRAPPER_PADDING_Y = 10;
const WRAPPER_PADDING_X = 15;

const StyledWrapper = styled.div`
  padding: ${WRAPPER_PADDING_Y}px ${WRAPPER_PADDING_X}px;
  width: 100%;
`;

function ObjectField({ name, schemaItem }: ObjectFieldProps) {
  const renderFields = () => {
    return schemaItem.children.map((schemaItem) => {
      const fieldName = name ? `${name}.${schemaItem.name}` : schemaItem.name;

      return fieldRenderer(fieldName, schemaItem);
    });
  };

  return <StyledWrapper>{renderFields()}</StyledWrapper>;
}

ObjectField.componentDefaultValues = {};

export default ObjectField;
