import { Alert } from 'antd'
import type { UsePermissionsResult } from '../hooks/usePermissions'

/**
 * 账号页（UserPanel）与角色页（PermissionPanel）**共用**的写操作反馈。
 *
 * 原样显示后端那句话（理由见 usePermissions 的文件头），两页共用一份实现：
 * 抄成两份的话，同一次写操作在两个页面上迟早给出不同的说法。
 */
export function PermissionFeedback({ permissions }: { permissions: UsePermissionsResult }) {
  return (
    <>
      {permissions.feedback.status === 'ok' && (
        <Alert type="success" showIcon closable title={permissions.feedback.data.message}
          onClose={permissions.clearFeedback} />
      )}
      {permissions.feedback.status === 'error' && (
        <Alert type="error" showIcon closable title={permissions.feedback.error}
          onClose={permissions.clearFeedback} />
      )}
    </>
  )
}
