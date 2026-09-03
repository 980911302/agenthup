import request from '@/utils/request'

// 查询 AI 定时任务列表
export function listJob(query) {
  return request({
    url: '/ai/job/list',
    method: 'get',
    params: query
  })
}

// 查询 AI 定时任务详情
export function getJob(jobId) {
  return request({
    url: '/ai/job/' + jobId,
    method: 'get'
  })
}

// 新增 AI 定时任务
export function addJob(data) {
  return request({
    url: '/ai/job',
    method: 'post',
    data: data
  })
}

// 修改 AI 定时任务
export function updateJob(data) {
  return request({
    url: '/ai/job',
    method: 'put',
    data: data
  })
}

// 删除 AI 定时任务
export function delJob(jobIds) {
  return request({
    url: '/ai/job/' + jobIds,
    method: 'delete'
  })
}

// 启用 / 暂停
export function changeJobStatus(jobId, status) {
  return request({
    url: '/ai/job/changeStatus',
    method: 'put',
    data: { jobId, status }
  })
}

// 立即执行一次(与调度同源派发)
export function runJob(jobId) {
  return request({
    url: '/ai/job/run/' + jobId,
    method: 'post'
  })
}

// 预览 cron 后续触发时刻(默认 5 次)
export function nextFireTimes(cronExpression, timezone) {
  return request({
    url: '/ai/job/nextFireTimes',
    method: 'get',
    params: { cronExpression, timezone }
  })
}

// 查询触发日志列表
export function listJobLog(query) {
  return request({
    url: '/ai/jobLog/list',
    method: 'get',
    params: query
  })
}

// 删除触发日志
export function delJobLog(logIds) {
  return request({
    url: '/ai/jobLog/' + logIds,
    method: 'delete'
  })
}
