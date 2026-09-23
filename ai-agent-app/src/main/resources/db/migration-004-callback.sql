-- 回调通知：任务终态后 POST 到调用方指定地址，记录地址与投递结果。
ALTER TABLE agent_task
    ADD COLUMN callback_url    VARCHAR(1024) NULL COMMENT '任务完成后通知地址；NULL=不通知' AFTER model_name,
    ADD COLUMN callback_status VARCHAR(16)   NULL COMMENT '回调投递结果：NULL=未配置, success, failed' AFTER callback_url,
    ADD COLUMN callback_at     DATETIME(3)   NULL COMMENT '最后一次回调时间' AFTER callback_status;
