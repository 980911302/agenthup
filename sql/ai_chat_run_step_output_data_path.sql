-- 超长 run_step.output_data 外置到文件,表内只留预览。断线恢复不读回此文件。
alter table ai_chat_run_step
  add column output_data_path varchar(512) default null comment '超长 output 外置路径' after output_data;
