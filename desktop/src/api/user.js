import request from './request'

// 查询用户个人信息
export function getUserProfile() {
  return request({
    url: '/system/user/profile/desktop',
    method: 'get'
  })
}

// 修改用户个人信息
export function updateUserProfile(data) {
  return request({
    url: '/system/user/profile/desktop',
    method: 'put',
    data
  })
}

// 修改用户密码
export function updateUserPwd(oldPassword, newPassword) {
  return request({
    url: '/system/user/profile/updatePwd',
    method: 'put',
    data: { oldPassword, newPassword }
  })
}

// 用户头像上传
export function uploadAvatar(file) {
  const formData = new FormData()
  formData.append('avatarfile', file)
  return request({
    url: '/system/user/profile/avatar',
    method: 'post',
    data: formData
  })
}
