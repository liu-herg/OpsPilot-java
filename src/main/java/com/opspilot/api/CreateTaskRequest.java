package com.opspilot.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(@NotBlank @Size(max = 2000) String request) {
}
