package com.opspilot.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DecisionRequest(@NotBlank String decision, @NotBlank @Size(max = 120) String actor) {
}
