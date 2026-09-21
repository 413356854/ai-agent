package ai.agent.entity.agent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 选项集与库里那一列（TEXT 存 JSON 数组）之间的编解码。
 *
 * <h2>为什么单独一个类，而不是两行写在服务层</h2>
 * 写的方向在服务层，读的方向在 {@code MapperRows}（它转 domain 记录）——
 * 两处各写一遍就有两种格式，而它们的差异要等到"写进去的选项读出来是空的"才发现。
 * 放在实体旁边，两端共用同一对方法。
 *
 * <h2>解码为什么是宽松的</h2>
 * 这一列的内容只由本代码写入，认不出的唯一来源是手工改库。那时返回空列表（= 只收自由回复），
 * 而不是抛 —— 抛的后果是整块看板因为一行坏数据读不出来，而"选项按钮不见了"
 * 至少是看得见、改得掉的（与 {@link ConfirmType#parse} 同一条取值）。
 */
public final class ConfirmOptions {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ConfirmOptions() {
	}

	/** 编成 JSON 数组；空列表编成 {@code null}（库里存 NULL = 没有选项，与"空数组"不分成两种存法）。 */
	public static String encode(List<String> options) {
		if (options == null || options.isEmpty()) {
			return null;
		}
		ArrayNode array = MAPPER.createArrayNode();
		options.forEach(array::add);
		return array.toString();
	}

	/** 从 JSON 数组解出来；空 / 坏内容返回空列表（见类注释）。 */
	public static List<String> decode(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			JsonNode root = MAPPER.readTree(json);
			if (!root.isArray()) {
				return List.of();
			}
			List<String> options = new ArrayList<>();
			for (JsonNode node : root) {
				String option = node.asText("");
				if (!option.isBlank()) {
					options.add(option);
				}
			}
			return List.copyOf(options);
		}
		catch (Exception e) {
			return List.of();
		}
	}

}
