import request from './request'

/** 当前用户已经拥有的技能；公共目录技能添加到“我的”后才会出现。 */
export function listSkillOptions() {
  return request({ url: '/ai/skill/options', method: 'get' })
}

/** 桌面端资源库：公共技能 + 当前用户自己的私有技能。 */
export function listDesktopSkills() {
  return request({ url: '/ai/skill/desktop', method: 'get' })
}

// 查询技能列表
export function listSkill(query) {
  return request({
    url: '/ai/skill/list',
    method: 'get',
    params: query
  })
}

// 查询技能详细
export function getSkill(skillId) {
  return request({
    url: '/ai/skill/' + skillId,
    method: 'get'
  })
}

// 新增技能
export function addSkill(data) {
  return request({
    url: '/ai/skill',
    method: 'post',
    data: data
  })
}

// 修改技能
export function updateSkill(data) {
  return request({
    url: '/ai/skill',
    method: 'put',
    data: data
  })
}

// 删除技能
export function delSkill(skillIds) {
  return request({
    url: '/ai/skill/' + skillIds,
    method: 'delete'
  })
}

/**
 * 技能附件(渐进披露第三层)。
 *
 * 只回元数据不回内容:内容由模型在会话里用 read 打开 ——
 * 这正是「技能带参考文件」和「把文档内联进提示词」的区别。
 */
export function listSkillFiles(skillId) {
  return request({
    url: '/ai/skill/' + skillId + '/files',
    method: 'get'
  })
}

/**
 * 上传技能附件,同名覆盖。
 * @param {string} summary 一句话说明,会进 loadSkill 的清单给模型看
 */
export function uploadSkillFile(skillId, file, summary) {
  const data = new FormData()
  data.append('file', file)
  if (summary) data.append('summary', summary)
  return request({
    url: '/ai/skill/' + skillId + '/files',
    method: 'post',
    data,
    headers: { repeatSubmit: false },
    timeout: 120000
  })
}

export function delSkillFile(skillId, fileId) {
  return request({
    url: '/ai/skill/' + skillId + '/files/' + fileId,
    method: 'delete'
  })
}
