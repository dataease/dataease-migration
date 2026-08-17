SET SQL_SAFE_UPDATES = 0;
UPDATE core_menu SET auth = TRUE WHERE id = 64;
UPDATE core_menu SET menu_sort = 4, auth = TRUE WHERE id = 16;

ALTER TABLE xpack_log MODIFY COLUMN oid BIGINT NULL COMMENT '组织ID';
ALTER TABLE xpack_share MODIFY COLUMN oid BIGINT NULL COMMENT '组织ID';
ALTER TABLE xpack_report_task MODIFY COLUMN oid BIGINT NULL COMMENT '所属组织ID';
ALTER TABLE xpack_threshold_info MODIFY COLUMN oid BIGINT NULL COMMENT '所属组织';
ALTER TABLE xpack_threshold_info_snapshot MODIFY COLUMN oid BIGINT NULL COMMENT '所属组织';
ALTER TABLE xpack_webhook MODIFY COLUMN oid BIGINT NULL COMMENT '组织ID';

CREATE TABLE IF NOT EXISTS per_permission (
    id BIGINT NOT NULL COMMENT '主键',
    subject_type INT NOT NULL COMMENT '主体类型: 0=user, 1=role, 2=org',
    subject_id BIGINT NOT NULL COMMENT '主体ID: uid / rid / oid',
    resource_type INT NOT NULL COMMENT '资源类型: 0=menu,1=panel,2=screen,3=dataset,4=datasource,8=dataFilling,10=spreadsheet',
    resource_id BIGINT NOT NULL COMMENT '资源ID，0表示根节点',
    weight INT NOT NULL DEFAULT 0 COMMENT '权重: 0=无,1=读,5=管理,7=使用,9=管理员',
    ext INT NOT NULL DEFAULT 0 COMMENT '扩展标志位',
    inherit TINYINT(1) NOT NULL COMMENT '是否从父资源继承',
    oid BIGINT NULL COMMENT '所属组织',
    create_time BIGINT NULL COMMENT '创建时间',
    creator BIGINT NULL COMMENT '创建者用户ID',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '权限';
ALTER TABLE per_busi_resource ADD COLUMN org_root TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否组织根目录（随组织生命周期）';

SET @de_upgrade_timestamp = FLOOR(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000);
SET @de_upgrade_sequence = 0;
DROP TEMPORARY TABLE IF EXISTS tmp_de_upgrade_org_root_parent;
DROP TEMPORARY TABLE IF EXISTS tmp_de_upgrade_org_root;
CREATE TEMPORARY TABLE tmp_de_upgrade_org_root (
    id BIGINT NOT NULL PRIMARY KEY,
    org_id BIGINT NOT NULL,
    rt_id INT NOT NULL,
    UNIQUE KEY uk_tmp_de_upgrade_org_root (org_id, rt_id)
) ENGINE = MEMORY;

INSERT INTO tmp_de_upgrade_org_root (id, org_id, rt_id)
SELECT ((@de_upgrade_timestamp - 1480166465631 + (@de_upgrade_sequence := @de_upgrade_sequence + 1)) << 22) + 135168,
       missing.org_id, missing.rt_id
FROM (
    SELECT org.id AS org_id, resource_type.rt_id
    FROM per_org org
    CROSS JOIN (
        SELECT 1 AS rt_id UNION ALL SELECT 2 UNION ALL SELECT 3
        UNION ALL SELECT 4 UNION ALL SELECT 8 UNION ALL SELECT 10
    ) resource_type
    LEFT JOIN per_busi_resource root ON root.org_id = org.id AND root.rt_id = resource_type.rt_id AND root.org_root = TRUE
    WHERE root.id IS NULL
    ORDER BY org.id, resource_type.rt_id
) missing;

CREATE TEMPORARY TABLE tmp_de_upgrade_org_root_parent LIKE tmp_de_upgrade_org_root;
INSERT INTO tmp_de_upgrade_org_root_parent (id, org_id, rt_id)
SELECT id, org_id, rt_id FROM tmp_de_upgrade_org_root;
INSERT INTO per_busi_resource (
    id, name, rt_id, org_id, pid, root_path, leaf, extra_flag, extra_flag1, org_root, create_time, creator
)
SELECT new_root.id, org.name, new_root.rt_id, new_root.org_id,
       COALESCE(parent_root.id, parent_new_root.id, 0), NULL, FALSE, 0, 1, TRUE, @de_upgrade_timestamp, 1
FROM tmp_de_upgrade_org_root new_root
JOIN per_org org ON org.id = new_root.org_id
LEFT JOIN (
    SELECT org_id, rt_id, MAX(id) AS id FROM per_busi_resource WHERE org_root = TRUE GROUP BY org_id, rt_id
) parent_root ON parent_root.org_id = org.pid AND parent_root.rt_id = new_root.rt_id
LEFT JOIN tmp_de_upgrade_org_root_parent parent_new_root
    ON parent_new_root.org_id = org.pid AND parent_new_root.rt_id = new_root.rt_id;
DROP TEMPORARY TABLE tmp_de_upgrade_org_root_parent;
DROP TEMPORARY TABLE tmp_de_upgrade_org_root;

UPDATE per_busi_resource pbr
JOIN per_busi_resource root ON root.org_id = pbr.org_id AND root.rt_id = pbr.rt_id AND root.org_root = TRUE
SET pbr.pid = root.id, pbr.root_path = CONCAT(root.id, '')
WHERE pbr.pid = 0 AND pbr.org_id IS NOT NULL AND pbr.org_root = FALSE;

ALTER TABLE per_role ADD COLUMN type_code INT NOT NULL DEFAULT 0 COMMENT '角色类型编码0普通用户7数据分析师9组织管理员';
UPDATE per_role SET type_code = 9 WHERE name = 'i18n_org_admin';
UPDATE per_role SET type_code = 7 WHERE name = 'i18n_org_analyst';
UPDATE per_role SET type_code = 0 WHERE name = 'i18n_ordinary_role';
UPDATE per_role child JOIN per_role parent ON child.pid = parent.id
SET child.type_code = parent.type_code WHERE child.pid != 0;

INSERT INTO per_role (id, name, `desc`, level, readonly, org_id, pid, type_code)
SELECT ((@de_upgrade_timestamp - 1480166465631 + (@de_upgrade_sequence := @de_upgrade_sequence + 1)) << 22) + 135168,
       'i18n_org_analyst', '数据分析师', 2, FALSE, org.id, 0, 7
FROM per_org org
WHERE NOT EXISTS (SELECT 1 FROM per_role role WHERE role.org_id = org.id AND role.type_code = 7);

INSERT IGNORE INTO per_permission (
    id, subject_type, subject_id, resource_type, resource_id, weight, ext, inherit, oid
) SELECT id, 0, uid, resource_type, resource_id, weight, ext, inherit, oid FROM per_auth_busi_user;
INSERT IGNORE INTO per_permission (
    id, subject_type, subject_id, resource_type, resource_id, weight, ext, inherit, oid
) SELECT pabr.id, 1, pabr.rid, pabr.resource_type, pabr.resource_id, pabr.weight, pabr.ext, pabr.inherit, pr.org_id
FROM per_auth_busi_role pabr JOIN per_role pr ON pabr.rid = pr.id WHERE pr.pid != 0;
INSERT IGNORE INTO per_permission (
    id, subject_type, subject_id, resource_type, resource_id, weight, ext, inherit, oid
) SELECT FLOOR(RAND() * 8000000000000000000) + 1000000000000000000,
         0, pur.uid, pabr.resource_type, pabr.resource_id, pabr.weight, pabr.ext, pabr.inherit, pur.oid
FROM per_auth_busi_role pabr
JOIN per_role pr ON pabr.rid = pr.id
JOIN per_user_role pur ON pur.rid = pabr.rid AND pur.oid = pr.org_id
JOIN per_user pu ON pur.uid = pu.id
WHERE pr.pid != 0 AND pur.oid != pu.default_oid;
INSERT IGNORE INTO per_permission (
    id, subject_type, subject_id, resource_type, resource_id, weight, ext, inherit, oid
) SELECT pam.id, 1, pam.rid, 0, pam.resource_id, pam.weight, 0, 0, pr.org_id
FROM per_auth_menu pam JOIN per_role pr ON pam.rid = pr.id
WHERE pr.pid != 0 AND pam.resource_id != 9;
INSERT IGNORE INTO per_permission (
    id, subject_type, subject_id, resource_type, resource_id, weight, ext, inherit, oid
) SELECT FLOOR(RAND() * 8000000000000000000) + 1000000000000000000,
         0, pur.uid, 0, pam.resource_id, pam.weight, 0, 0, pur.oid
FROM per_auth_menu pam
JOIN per_role pr ON pam.rid = pr.id
JOIN per_user_role pur ON pur.rid = pam.rid AND pur.oid = pr.org_id
JOIN per_user pu ON pur.uid = pu.id
WHERE pr.pid != 0 AND pur.oid != pu.default_oid AND pam.resource_id != 9;
INSERT IGNORE INTO per_permission (
    id, subject_type, subject_id, resource_type, resource_id, weight, ext, inherit, oid
) SELECT FLOOR(RAND() * 8000000000000000000) + 1000000000000000000,
         0, pur.uid, pbr.rt_id, pbr.id, CASE WHEN pr.readonly = 0 THEN 9 ELSE 1 END, 0, 0, pur.oid
FROM per_user_role pur
JOIN per_user pu ON pur.uid = pu.id
JOIN per_role pr ON pur.rid = pr.id AND pur.oid = pr.org_id
JOIN per_busi_resource pbr ON pbr.org_id = pur.oid
WHERE pur.oid != pu.default_oid AND pu.default_oid IS NOT NULL AND pr.pid = 0;
INSERT IGNORE INTO per_permission (
    id, subject_type, subject_id, resource_type, resource_id, weight, ext, inherit, oid
) SELECT FLOOR(RAND() * 8000000000000000000) + 1000000000000000000,
         0, pur.uid, 0, cm.id, CASE WHEN pr.readonly = 0 THEN 9 ELSE 1 END, 0, 0, pur.oid
FROM per_user_role pur
JOIN per_user pu ON pur.uid = pu.id
JOIN per_role pr ON pur.rid = pr.id AND pur.oid = pr.org_id
CROSS JOIN core_menu cm
WHERE pur.oid != pu.default_oid AND pu.default_oid IS NOT NULL AND pr.pid = 0
  AND cm.hidden = 0 AND cm.id NOT IN (15, 16, 64)
  AND (pr.readonly = 0 OR cm.id NOT IN (7, 8, 10));
INSERT IGNORE INTO per_permission (
    id, subject_type, subject_id, resource_type, resource_id, weight, ext, inherit, oid
) SELECT DISTINCT FLOOR(RAND() * 8000000000000000000) + 1000000000000000000,
         pp.subject_type, pp.subject_id, pp.resource_type, root.id, pp.weight, pp.ext, pp.inherit, pp.oid
FROM per_permission pp
JOIN per_busi_resource pbr ON pp.resource_id = pbr.id
JOIN per_busi_resource root ON root.org_id = pbr.org_id AND root.rt_id = pbr.rt_id AND root.org_root = TRUE
WHERE pp.resource_type != 0
  AND NOT EXISTS (
      SELECT 1 FROM per_permission existing
      WHERE existing.subject_type = pp.subject_type AND existing.subject_id = pp.subject_id
        AND existing.resource_type = pp.resource_type AND existing.resource_id = root.id
  );
CREATE INDEX idx_group_key ON per_permission(subject_type,subject_id,resource_type,resource_id,id);

UPDATE per_permission pp
INNER JOIN (
    SELECT MIN(id) AS keep_id, MAX(weight) AS max_weight, BIT_OR(ext) AS merge_ext
    FROM per_permission
    GROUP BY subject_type,subject_id,resource_type,resource_id
    HAVING COUNT(*) > 1
) t ON pp.id = t.keep_id
SET pp.weight = t.max_weight, pp.ext = t.merge_ext;
DELETE pp
FROM per_permission pp
INNER JOIN (
    SELECT MIN(id) min_id,subject_type,subject_id,resource_type,resource_id
    FROM per_permission GROUP BY subject_type,subject_id,resource_type,resource_id
) t
WHERE pp.subject_type = t.subject_type AND pp.subject_id = t.subject_id
  AND pp.resource_type = t.resource_type AND pp.resource_id = t.resource_id AND pp.id > t.min_id;
DELETE pp2
FROM per_permission pp2
JOIN per_permission pp1
    ON pp1.subject_type = pp2.subject_type AND pp1.subject_id = pp2.subject_id
    AND pp1.resource_type = pp2.resource_type AND pp1.resource_id = pp2.resource_id AND pp1.id < pp2.id;
DELETE pur
FROM per_user_role pur
JOIN per_user pu ON pur.uid = pu.id
WHERE pur.oid != pu.default_oid AND pu.default_oid IS NOT NULL;
SET @de_upgrade_timestamp = NULL;
SET @de_upgrade_sequence = NULL;


INSERT INTO `de_standalone_version` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES ('100', '1.40', 'ddl', 'SQL', 'V1.40__ddl.sql', '0', 'system', '2026-07-08 13:28:37', '47165', '1');
INSERT INTO `de_standalone_version` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES ('101', '2.40', 'ddl', 'SQL', 'V2.40__ddl.sql', '0', 'system', '2026-07-08 13:29:24', '3401', '1');
INSERT INTO `de_standalone_version` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES ('102', '3.40', 'ddl', 'SQL', 'V3.40__ddl.sql', '0', 'system', '2026-07-08 13:29:27', '178', '1');
