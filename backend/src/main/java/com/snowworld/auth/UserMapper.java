package com.snowworld.auth;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper {

    @Data
    class UserRow {
        private Long id;
        private String username;
        private String passwordHash;
        private String displayName;
        private String phoneMasked;
        private String status;
        private boolean allSites;
        private OffsetDateTime createdAt;
    }

    @Select("SELECT * FROM app_user WHERE username = #{username}")
    UserRow findByUsername(String username);

    @Select("SELECT * FROM app_user WHERE id = #{id}")
    UserRow findById(long id);

    @Select("SELECT * FROM app_user ORDER BY id")
    List<UserRow> findAll();

    @Select("SELECT role_code FROM user_role WHERE user_id = #{userId} ORDER BY role_code")
    List<String> findRoles(long userId);

    @Select("""
            SELECT s.id FROM scenic_site s
            WHERE (SELECT all_sites FROM app_user WHERE id = #{userId} AND status = 'ACTIVE')
               OR s.id IN (SELECT site_id FROM user_scenic_scope sc
                           JOIN app_user u ON u.id = sc.user_id
                           WHERE sc.user_id = #{userId} AND u.status = 'ACTIVE')
            ORDER BY s.sort_order, s.id
            """)
    List<Long> findAllowedSiteIds(long userId);

    @Select("""
            SELECT DISTINCT u.id, u.display_name FROM app_user u
            JOIN user_role r ON r.user_id = u.id
            LEFT JOIN user_scenic_scope sc ON sc.user_id = u.id
            WHERE u.status = 'ACTIVE' AND r.role_code = #{role} AND (u.all_sites OR sc.site_id = #{siteId})
            ORDER BY u.id
            """)
    List<UserRow> findByRoleAndSite(@Param("role") String role, @Param("siteId") long siteId);

    @Insert("""
            INSERT INTO app_user (username, password_hash, display_name, phone_masked, all_sites)
            VALUES (#{username}, #{passwordHash}, #{displayName}, #{phoneMasked}, #{allSites})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(UserRow row);

    @Update("UPDATE app_user SET display_name = #{displayName}, status = #{status}, all_sites = #{allSites}, updated_at = now() WHERE id = #{id}")
    int update(UserRow row);

    @Update("UPDATE app_user SET password_hash = #{hash}, updated_at = now() WHERE id = #{id}")
    int updatePassword(@Param("id") long id, @Param("hash") String hash);

    @Delete("DELETE FROM user_role WHERE user_id = #{userId}")
    void clearRoles(long userId);

    @Insert("INSERT INTO user_role (user_id, role_code) VALUES (#{userId}, #{role})")
    void addRole(@Param("userId") long userId, @Param("role") String role);

    @Delete("DELETE FROM user_scenic_scope WHERE user_id = #{userId}")
    void clearScope(long userId);

    @Insert("INSERT INTO user_scenic_scope (user_id, site_id) VALUES (#{userId}, #{siteId})")
    void addScope(@Param("userId") long userId, @Param("siteId") long siteId);

    @Select("SELECT site_id FROM user_scenic_scope WHERE user_id = #{userId} ORDER BY site_id")
    List<Long> findExplicitScope(long userId);

    @Select("SELECT count(*) FROM app_user")
    long count();
}
