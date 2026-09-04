-- AutoSense device 表：平台代理建机模型 -> 按 SN 绑定已有模拟器设备模型。
-- 适用基线：2026-08-27 schema。执行前请备份；本脚本不会删除任何 device 行。
-- 已知旧数据仅允许 smart_bulb + LA001/LB001。遇到无法可靠回填的行会先中止并报告，
-- 不会以占位 ID 或删除记录的方式掩盖问题。

DELIMITER //
CREATE PROCEDURE migrate_device_sn_binding()
BEGIN
    DECLARE invalid_sn_count BIGINT DEFAULT 0;
    DECLARE unsupported_count BIGINT DEFAULT 0;
    DECLARE failure_message VARCHAR(255);

    SELECT COUNT(*) INTO invalid_sn_count
      FROM device
     WHERE identifier IS NULL
        OR BINARY identifier NOT REGEXP '^[A-Z0-9]{4}[0-9]{9}$';
    IF invalid_sn_count > 0 THEN
        SET failure_message = CONCAT(
                'device migration aborted: ', invalid_sn_count,
                ' row(s) have an invalid SN; repair them before rerunning');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = failure_message;
    END IF;

    SELECT COUNT(*) INTO unsupported_count
      FROM device
     WHERE device_type <> 'smart_bulb'
        OR device_model NOT IN ('LA001', 'LB001');
    IF unsupported_count > 0 THEN
        SET failure_message = CONCAT(
                'device migration aborted: ', unsupported_count,
                ' row(s) cannot be mapped to stable simulator type/model IDs');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = failure_message;
    END IF;

    ALTER TABLE device
        ADD COLUMN simulator_name VARCHAR(128) NULL AFTER name,
        ADD COLUMN device_type_code VARCHAR(32) NULL AFTER simulator_device_id,
        ADD COLUMN device_type_id BIGINT NULL AFTER device_type_code,
        ADD COLUMN device_model_code VARCHAR(32) NULL AFTER device_type_id,
        ADD COLUMN device_model_id BIGINT NULL AFTER device_model_code;

    UPDATE device
       SET simulator_name = name,
           device_type_code = 'LITE',
           device_type_id = 1,
           device_model_code = device_model,
           device_model_id = CASE device_model WHEN 'LA001' THEN 1 WHEN 'LB001' THEN 2 END;

    ALTER TABLE device
        DROP INDEX identifier,
        CHANGE COLUMN identifier sn CHAR(13) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        MODIFY COLUMN simulator_name VARCHAR(128) NOT NULL,
        MODIFY COLUMN device_type_code VARCHAR(32) NOT NULL,
        MODIFY COLUMN device_type_id BIGINT NOT NULL,
        MODIFY COLUMN device_model_code VARCHAR(32) NOT NULL,
        MODIFY COLUMN device_model_id BIGINT NOT NULL,
        ADD UNIQUE KEY uk_device_sn (sn),
        ADD INDEX idx_device_type_model (device_type_code, device_model_code),
        DROP INDEX idx_device_type,
        DROP COLUMN device_type,
        DROP COLUMN device_model,
        DROP COLUMN status,
        DROP COLUMN last_seen_at;
END//
CALL migrate_device_sn_binding()//
DROP PROCEDURE migrate_device_sn_binding//
DELIMITER ;
