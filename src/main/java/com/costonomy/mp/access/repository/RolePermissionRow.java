package com.costonomy.mp.access.repository;

/** A single role → permission mapping, read as a projection. */
public record RolePermissionRow(Long roleId, String roleCode, String permissionCode) {
}
