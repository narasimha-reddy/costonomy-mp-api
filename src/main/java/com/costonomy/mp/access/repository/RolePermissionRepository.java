package com.costonomy.mp.access.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import com.costonomy.mp.access.domain.Role;

import java.util.List;

/**
 * Reads the role → permission mapping.
 *
 * <p>{@code role_permission} is a join table with a composite key and no entity
 * of its own — nothing in the domain needs to treat a grant as an object, and an
 * {@code @IdClass} entity would only add ceremony.
 */
public interface RolePermissionRepository extends JpaRepository<Role, Long> {

    @Query(value = """
            select rp.role_id as roleId, r.code as roleCode, p.code as permissionCode
              from role_permission rp
              join role r on r.id = rp.role_id
              join permission p on p.id = rp.permission_id
             where r.status = 'ACTIVE'
            """, nativeQuery = true)
    List<Object[]> findAllMappings();
}
