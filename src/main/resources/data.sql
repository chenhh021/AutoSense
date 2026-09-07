-- 种子数据(T006):修复知识库首批内容(仅智能灯泡;路由器/空调待模拟器扩展型号后补充)
-- 设备不再预置种子:设备经 POST /api/v1/devices 登记接口创建(FR-020,本平台代建)
INSERT INTO repair_knowledge
 (device_type, problem_pattern, solution_content, auto_executable, repair_action_code, disruptive, manual_steps)
SELECT 'smart_bulb', '不亮,亮度为 0,太暗',
  '调整亮度至正常水平', 1, 'set_brightness', 0, NULL
WHERE NOT EXISTS (SELECT 1 FROM repair_knowledge WHERE device_type = 'smart_bulb'
 AND problem_pattern = '不亮,亮度为 0,太暗');

INSERT INTO repair_knowledge
 (device_type, problem_pattern, solution_content, auto_executable, repair_action_code, disruptive, manual_steps)
SELECT 'smart_bulb', '设备已停止,离线,不响应',
  '启动设备恢复运行', 1, 'start', 0, NULL
WHERE NOT EXISTS (SELECT 1 FROM repair_knowledge WHERE device_type = 'smart_bulb'
 AND problem_pattern = '设备已停止,离线,不响应');

INSERT INTO repair_knowledge
 (device_type, problem_pattern, solution_content, auto_executable, repair_action_code, disruptive, manual_steps)
SELECT 'smart_bulb', '硬件故障,灯珠损坏,闪烁异常',
  '灯泡硬件故障需人工检查更换', 0, NULL, 0,
  '1. 关闭灯具电源
2. 待灯泡冷却后拧下灯泡
3. 检查灯珠与灯座触点是否烧蚀
4. 更换同型号灯泡后重新上电'
WHERE NOT EXISTS (SELECT 1 FROM repair_knowledge WHERE device_type = 'smart_bulb'
 AND problem_pattern = '硬件故障,灯珠损坏,闪烁异常');

INSERT INTO repair_knowledge
 (device_type, problem_pattern, solution_content, auto_executable, repair_action_code, disruptive, manual_steps)
SELECT 'smart_bulb', '色温异常,颜色不对,色温偏高',
  '色温漂移超出远程调节范围,需人工检查', 0, NULL, 0,
  '1. 关闭灯具电源
2. 待灯泡冷却后检查灯罩是否变色或积尘
3. 重新上电观察色温是否恢复
4. 仍未恢复则更换同型号灯泡'
WHERE NOT EXISTS (SELECT 1 FROM repair_knowledge WHERE device_type = 'smart_bulb'
 AND problem_pattern = '色温异常,颜色不对,色温偏高');

-- 初始管理员种子(US3,R23):账号 admin,明文 admin123 的预计算 BCrypt 散列;
-- INSERT IGNORE + uk_userAccount 保证重复启动幂等。上线后请立即改密。
INSERT IGNORE INTO user (userAccount, userPassword, userName, userRole) VALUES
 ('admin', '$2a$10$pUSFlBFvrSpAndsvnc8uTu2dYK/0fI/9ax2yxOYUREQIXpKWq3.1C', '管理员', 'admin');
