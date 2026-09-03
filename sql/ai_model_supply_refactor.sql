-- 供应管理改造:绑定收进模型管理
-- 1. ai_model_channel 增加重试次数(路由调用失败时在该渠道的重试次数,0=不重试)
alter table ai_model_channel add column retry_count int null default 0 comment '失败重试次数(0=不重试)' after weight;

-- 2. 「模型渠道绑定」全局菜单隐藏(管理入口收进 模型管理-供应 弹窗,页面/API 保留,恢复时将 visible 改回 '0')
update sys_menu set visible = '1' where perms = 'ai:modelChannel:list' and menu_type = 'C';
