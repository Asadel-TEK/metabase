/**
 * ⚠️
 * @deprecated use existing types from, or add to metabase-types/api/*
 */

import { ISO8601Time } from ".";
import { Table, TableId } from "./Table";
import { Value } from "./Dataset";

export type FieldId = number;

export type BaseType = string;
export type SemanticType = string;

export type FieldVisibilityType =
  | "details-only"
  | "hidden"
  | "normal"
  | "retired";

export type Field = {
  id: FieldId;

  name: string;
  display_name: string;
  description: string;
  base_type: BaseType;
  effective_type?: BaseType;
  semantic_type: SemanticType;
  active: boolean;
  visibility_type: FieldVisibilityType;
  preview_display: boolean;
  position: number;
  parent_id?: FieldId;

  table_id: TableId;

  fk_target_field_id?: FieldId;

  max_value?: number;
  min_value?: number;

  caveats?: string | null;
  points_of_interest?: string;

  table: Table;

  last_analyzed: ISO8601Time;
  created_at: ISO8601Time;
  updated_at: ISO8601Time;

  values?: FieldValues;
  dimensions?: FieldDimension;
};

export type RawFieldValue = Value;
export type HumanReadableFieldValue = string;

export type FieldValue =
  | [RawFieldValue]
  | [RawFieldValue, HumanReadableFieldValue];
export type FieldValues = FieldValue[];

export type FieldDimension = {
  name: string;
};
