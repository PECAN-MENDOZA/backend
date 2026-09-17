package com.mvp.backend.teacher.application.dto;

import jakarta.validation.constraints.Size;

public record UpdateClassroomRequest(@Size(min = 1, max = 80) String name, Boolean archived) {
}
