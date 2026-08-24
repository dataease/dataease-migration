SET SQL_SAFE_UPDATES = 0;

-- datasource_role 是 V3 JPA 查询源端/目标端列表的判定字段；迁移服务会在执行本脚本前幂等补列。
-- V2 内置 PostgreSQL 类型为 pg，V3 同步插件统一使用 postgresql。
UPDATE per_sync_datasource
SET type = 'postgresql'
WHERE LOWER(type) = 'pg';

-- V2 只有 Doris 作为内置目标端，其余内置同步数据源均为源端。
UPDATE per_sync_datasource
SET datasource_role = CASE WHEN LOWER(type) = 'doris' THEN 2 ELSE 1 END;

-- V2 任务参数保存了完整的数据源快照，需要同步迁移外层类型、快照类型和数据源角色。
-- 参数已经由迁移服务校验，因此一次完成全部转换，避免对 JSON 大字段连续六次全表扫描和重写。
-- JSON_REPLACE 不会创建缺失的可选 type 路径；已有 type 按同步任务模型约定均为字符串。
UPDATE per_sync_task_info
SET parameter = JSON_REPLACE(
        JSON_SET(parameter,
                 '$.source.datasource.datasourceRole', 1,
                 '$.target.datasource.datasourceRole', 2),
        '$.source.type',
        IF(LOWER(JSON_UNQUOTE(JSON_EXTRACT(parameter, '$.source.type'))) = 'pg',
           'postgresql', JSON_UNQUOTE(JSON_EXTRACT(parameter, '$.source.type'))),
        '$.source.datasource.type',
        IF(LOWER(JSON_UNQUOTE(JSON_EXTRACT(parameter, '$.source.datasource.type'))) = 'pg',
           'postgresql', JSON_UNQUOTE(JSON_EXTRACT(parameter, '$.source.datasource.type'))),
        '$.target.type',
        IF(LOWER(JSON_UNQUOTE(JSON_EXTRACT(parameter, '$.target.type'))) = 'pg',
           'postgresql', JSON_UNQUOTE(JSON_EXTRACT(parameter, '$.target.type'))),
        '$.target.datasource.type',
        IF(LOWER(JSON_UNQUOTE(JSON_EXTRACT(parameter, '$.target.datasource.type'))) = 'pg',
           'postgresql', JSON_UNQUOTE(JSON_EXTRACT(parameter, '$.target.datasource.type'))))
WHERE JSON_VALID(parameter)
  AND JSON_TYPE(JSON_EXTRACT(parameter, '$.source.datasource')) = 'OBJECT'
  AND JSON_TYPE(JSON_EXTRACT(parameter, '$.target.datasource')) = 'OBJECT';

-- 全新迁移后先暂停定时任务；V2 已停止后遗留的手动运行态则改为完成。
-- 合并为一次更新，减少任务表扫描和事务日志写入。
UPDATE per_sync_task_info
SET `_status` = CASE WHEN scheduler_type <> 'NONE' THEN 'SUSPEND' ELSE 'DONE' END,
    trigger_next_time = -1
WHERE scheduler_type <> 'NONE'
   OR (scheduler_type = 'NONE' AND `_status` IN ('RUNNING', 'Running', 'running'));

-- 历史日志量可能达到百万级。运行中日志转换由迁移服务单独执行并读取受影响行数，
-- 避免为了迁移前后统计再对 per_sync_task_log 做额外全表扫描。
UPDATE per_sync_task_lock
SET expiration_time = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND);
