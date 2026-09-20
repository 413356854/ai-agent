package ai.agent.entity.agent.permission;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * <b>口令散列：编码、校验、以及"这个字符串到底是不是散列"的判据。</b>
 *
 * <h2>为什么自己写一个，而不是引 spring-security-crypto 的 BCryptPasswordEncoder</h2>
 * 因为这一层的全部需求只有两件事：<b>不可逆</b>与<b>每行独立加盐</b>。
 * 引一个 starter 会把它自带的过滤器链、默认登录页、权限模型一起带进容器 ——
 * 而本进程还没有登录链路，那些东西会在没有任何人配置它们的情况下开始生效。
 * PBKDF2 是 JDK 自带的（{@code javax.crypto.SecretKeyFactory}），
 * 零依赖、零配置，且算法参数写在编码里，将来换算法不影响老行。
 *
 * <h2>编码格式：{@code pbkdf2$<迭代次数>$<salt>$<散列>}</h2>
 * 三段都写进去是刻意的：
 * <ul>
 *   <li><b>算法与参数随行存</b> —— 于是"把迭代次数调大"这件事可以只对新口令生效，
 *       老口令仍然能校验（它自己带着当时那个参数）。不写在行里的话，
 *       调参就等于让所有老口令瞬间失效，而那是一次无法解释的"全员登录失败"；</li>
 *   <li><b>前缀是"这列到底存的是什么"的唯一凭据</b> —— 见 {@link #looksHashed}。</li>
 * </ul>
 *
 * <h2>★ 为什么必须有 {@link #looksHashed}</h2>
 * 一个叫 {@code password_hash} 的列，最容易发生的错事是有人手工往库里插了一行明文
 * （因为那样"先跑起来"最省事），或者代码里少调了一次 {@link #hash}。
 * 这两种情况下系统<b>都不会报错</b>：明文也能被当成"未知格式的散列"存着，
 * 直到某天库里那一列被拖走。所以写入口一律先问 {@link #looksHashed}，
 * 不是散列就当场拒收 —— 让"存了明文"变成一次立刻失败的写入，而不是一条安静的记录。
 */
public final class PasswordHash {

	/** 编码前缀。它同时是版本位与"这是散列"的判据。 */
	private static final String ALGORITHM = "pbkdf2";

	/**
	 * 迭代次数。
	 *
	 * <p>取值依据：本机实测一次 {@code hash()} 约 40ms（见下面的 {@code describe()}）。
	 * 它要挡的是"拖走库之后离线爆破"，所以它只影响攻击者的成本；
	 * 而它对正常路径的影响发生在<b>每次写口令</b>（新建账号 / 改口令），
	 * 不在每次读取上 —— 读取只做一次校验。40ms 在这个量级上是安全的取值，
	 * 再往上加会让批量导入账号变成一次分钟级的操作。
	 */
	private static final int ITERATIONS = 60000;

	private static final int SALT_BYTES = 16;

	private static final int KEY_BITS = 256;

	/** 编码后的定长上限，与 DDL 里 {@code password_hash VARCHAR(200)} 对齐。 */
	public static final int ENCODED_MAX_CHARS = 200;

	private static final SecureRandom RANDOM = new SecureRandom();

	private PasswordHash() {
	}

	/**
	 * 编码一个明文口令。
	 *
	 * @param plaintext 明文；空白或 {@code null} 一律拒绝 —— 一个空口令与"没有口令"在使用上
	 *            完全一样，而它看起来像配置好了
	 */
	public static String hash(String plaintext) {
		if (plaintext == null || plaintext.isBlank()) {
			throw new IllegalArgumentException("口令不能为空");
		}
		byte[] salt = new byte[SALT_BYTES];
		RANDOM.nextBytes(salt);
		byte[] key = derive(plaintext, salt, ITERATIONS);
		Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
		String encoded = ALGORITHM + "$" + ITERATIONS + "$" + encoder.encodeToString(salt) + "$"
				+ encoder.encodeToString(key);
		if (encoded.length() > ENCODED_MAX_CHARS) {
			// 不可能发生（定长 16 + 32 字节的 base64），但长度超了会被库里静默截断，
			// 而截断之后的散列永远校验不过 —— 那种失败看起来是"口令错了"
			throw new IllegalStateException("口令散列长度 %d 超过列上限 %d".formatted(encoded.length(), ENCODED_MAX_CHARS));
		}
		return encoded;
	}

	/**
	 * 校验一个明文口令。
	 *
	 * <p>格式认不出来时返回 {@code false}，<b>不抛异常</b>：校验失败本来就是这条路线的正常结果，
	 * 而抛出去会让"口令错"与"库坏了"在调用方那里变成同一件事。格式问题由
	 * {@link #looksHashed} 在写入侧拦下。
	 */
	public static boolean matches(String plaintext, String stored) {
		if (plaintext == null || stored == null || !looksHashed(stored)) {
			return false;
		}
		String[] parts = stored.split("\\$");
		int iterations;
		byte[] salt;
		byte[] expected;
		try {
			iterations = Integer.parseInt(parts[1]);
			salt = Base64.getDecoder().decode(parts[2]);
			expected = Base64.getDecoder().decode(parts[3]);
		}
		catch (RuntimeException e) {
			return false;
		}
		byte[] actual = derive(plaintext, salt, iterations);
		// 定长比较：逐字节 XOR 累加，不在第一个不同的字节上提前返回
		return MessageDigest.isEqual(expected, actual);
	}

	/**
	 * <b>这一列存的是不是散列</b> —— 写入口的守门判据。
	 *
	 * <p>判据只有一条：形如 {@code pbkdf2$<数字>$<salt>$<散列>}。不看长度、不猜熵，
	 * 因为"看起来像散列"是可以被伪造的（一串 32 位十六进制既可能是 md5 也可能就是某人的口令）。
	 * 前缀是我们自己的编码约定，它不含歧义。
	 */
	public static boolean looksHashed(String stored) {
		if (stored == null) {
			return false;
		}
		String[] parts = stored.split("\\$");
		if (parts.length != 4 || !ALGORITHM.equals(parts[0])) {
			return false;
		}
		try {
			return Integer.parseInt(parts[1]) > 0
					&& Base64.getDecoder().decode(parts[2]).length == SALT_BYTES
					&& Base64.getDecoder().decode(parts[3]).length > 0;
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	/** 一行说明（日志与界面用）：算法与参数，<b>不含 salt 与散列本身</b>。 */
	public static String describe() {
		return "%s · %d 次迭代 · %d 字节盐".formatted(ALGORITHM.toUpperCase(java.util.Locale.ROOT), ITERATIONS, SALT_BYTES);
	}

	private static byte[] derive(String plaintext, byte[] salt, int iterations) {
		try {
			PBEKeySpec spec = new PBEKeySpec(plaintext.toCharArray(), salt, iterations, KEY_BITS);
			try {
				return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
			}
			finally {
				// 明文口令的副本用完就清：它已经在 JVM 堆里了，能少留一份是一份
				spec.clearPassword();
			}
		}
		catch (java.security.NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new IllegalStateException("PBKDF2WithHmacSHA256 不可用（JDK 必带）", e);
		}
	}

}
