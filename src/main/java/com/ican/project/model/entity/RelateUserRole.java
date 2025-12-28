package com.ican.project.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RelateUserRole {
    private String id;
    private String userId;
    private String roleId;
}
