package com.chh.autosense.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** POST /device/{sn}/get envelope. Device identity and model are resolved from local bindings. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DevicePropertiesResponse(String sn, Boolean success, JsonNode properties) {
}
