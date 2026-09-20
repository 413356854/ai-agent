package ai.agent.service.permission;

/**
 * 权限库层面的失败（连不上、SQL 错、表还没建）。
 *
 * <p>单独一个类型是为了让端点能分清"是权限库坏了"还是"调用方给的东西不对"。
 * 后者有明确的 4xx，而把两者混成一个 500 会让"参数写错了"看起来像一次服务故障。
 */
public class PermissionStoreException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public PermissionStoreException(String message, Throwable cause) {
		super(message, cause);
	}

}
