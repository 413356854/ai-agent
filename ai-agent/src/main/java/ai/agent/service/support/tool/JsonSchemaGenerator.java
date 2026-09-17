package ai.agent.service.support.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从 Java 类型 + JSR-380 注解生成 JSON Schema。
 *
 * <h2>为什么不是"手写 schema 字符串"，也不是"引一个 schema 库"</h2>
 * 参数约束在这条链路上有<b>两个消费者</b>：
 * <ol>
 *   <li><b>模型</b> —— 通过 {@code tools[].function.parameters} 看到 schema，据此产出参数；</li>
 *   <li><b>Java 端</b> —— 收到参数后必须<b>重新校验</b>（模型看到 schema 不等于它会遵守）。</li>
 * </ol>
 * 手写这两份必然漂移，而漂移的后果是一种特别难查的故障：
 * <b>模型看到的限制比实际校验松</b>，于是它"按说明调用了"却永远过不去 ——
 * 排查方向会跑到提示词和模型能力上，因为工具说明看起来完全没问题。
 *
 * <p>所以这里把约束的<b>唯一事实来源</b>定为 Java 类的注解，schema 由它生成：
 * 写一遍 `@Min(1) @Max(10)`，模型看到的是 `{"minimum":1,"maximum":10}`，
 * 校验器执行的也是 1~10。想改范围只有一处可改，两边不可能不一致。
 *
 * <h2>不支持的注解会<b>抛异常</b>，不是"忽略掉"</h2>
 * 这是本类最重要的一个决定。扫到<b>认得出来的 jakarta.validation 注解但不支持</b>时
 * 直接抛 {@link UnsupportedToolParameterException}（在启动期、注册工具时就抛）。
 * <p>如果改成"忽略未知注解"，就会重新制造出上面那个漂移 —— 只不过这次是<b>静默</b>的：
 * schema 里没有那条约束（模型不知道），而 {@code Validator} 仍然会执行它（会拒绝），
 * 于是又回到"模型按说明调用却永远过不去"。宁可启动就炸，也不要这种运行期幽灵。
 *
 * <h2>支持的子集（刻意列出来，而不是让它"大概能work"）</h2>
 * <table border="1">
 *   <caption>类型与约束的映射</caption>
 *   <tr><th>Java</th><th>JSON Schema</th></tr>
 *   <tr><td>{@code String}</td><td>{@code type: string}</td></tr>
 *   <tr><td>{@code int/long/Integer/Long}</td><td>{@code type: integer}</td></tr>
 *   <tr><td>{@code double/float/Double/Float/BigDecimal}</td><td>{@code type: number}</td></tr>
 *   <tr><td>{@code boolean/Boolean}</td><td>{@code type: boolean}</td></tr>
 *   <tr><td>{@code enum}</td><td>{@code type: string, enum: [...]}</td></tr>
 *   <tr><td>{@code List<T>} / {@code T[]}</td><td>{@code type: array, items: {...}}</td></tr>
 *   <tr><td>嵌套 {@code record}</td><td>递归展开的 {@code object}</td></tr>
 * </table>
 * <table border="1">
 *   <caption>约束映射（未列出的 jakarta.validation 注解 -> 抛异常）</caption>
 *   <tr><th>注解</th><th>JSON Schema</th></tr>
 *   <tr><td>{@code @NotNull}</td><td>加入 {@code required}</td></tr>
 *   <tr><td>{@code @NotBlank}</td><td>{@code minLength: 1} + {@code required}</td></tr>
 *   <tr><td>{@code @NotEmpty}</td><td>字符串 {@code minLength: 1}；数组/集合 {@code minItems: 1}；并加入 {@code required}</td></tr>
 *   <tr><td>{@code @Size(min,max)}</td><td>字符串 {@code minLength/maxLength}；数组 {@code minItems/maxItems}</td></tr>
 *   <tr><td>{@code @Min/@Max}</td><td>{@code minimum/maximum}</td></tr>
 *   <tr><td>{@code @DecimalMin/@DecimalMax}</td><td>{@code minimum/maximum}（值原样取自注解字符串）</td></tr>
 *   <tr><td>{@code @Pattern}</td><td>{@code pattern}（用 {@code regexp}，与 Jakarta 一致）</td></tr>
 *   <tr><td>{@code @Null}</td><td>故意不支持 —— 工具参数出现"必须为 null"没有实际场景，抛异常更诚实</td></tr>
 * </table>
 */
public final class JsonSchemaGenerator {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private JsonSchemaGenerator() {
	}

	/**
	 * 生成一个 object 类型的根 schema。
	 *
	 * @param parameterType 工具参数的 Java 类型（通常是 record）
	 * @param toolName 只用于异常信息（哪把工具的 schema 生成失败了）
	 */
	public static String generate(Class<?> parameterType, String toolName) {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("type", "object");
		ObjectNode properties = root.putObject("properties");
		ArrayNode required = root.putArray("required");

		// 排序保证可复现：同一个类生成的 schema 每次完全一致。
		// 不排序的话字段顺序取决于 JVM 反射顺序 —— 虽然 JSON 语义上无序，
		// 但"同一份代码生成的 schema 字符串不一样"会让**快照式断言和后端做缓存**都不可靠
		List<RecordComponent> components = new ArrayList<>(List.of(parameterType.getRecordComponents()));
		components.sort(Comparator.comparing(RecordComponent::getName));

		for (RecordComponent component : components) {
			String jsonName = jsonNameOf(component);
			ObjectNode propertySchema = schemaFor(component.getType(), component.getGenericType(), toolName, jsonName);
			Annotation[] annotations = constraintsOf(parameterType, component, toolName);
			applyConstraints(propertySchema, annotations, component.getType(), toolName, jsonName);
			properties.set(jsonName, propertySchema);

			if (isRequired(annotations, jsonName)) {
				required.add(jsonName);
			}
		}

		// 空 required 数组会让某些客户端困惑（也会让 schema 看起来像"什么都不必填"），
		// 无参工具干脆不写这个键 —— 语义等价，但读起来清楚
		if (required.isEmpty()) {
			root.remove("required");
		}
		// 参数对象不接受额外字段：模型很爱"顺手多给一个字段"（例如把说明塞进 notes），
		// 而 Jackson 默认会忽略它们 —— 忽略等于"用户以为生效了其实没有"（踩坑库 #20 同族）。
		// 显式禁掉，让它变成一条能被回填的错误
		root.put("additionalProperties", false);
		return root.toPrettyString();
	}

	/** 字段在 JSON 里的名字：优先 {@code @JsonProperty}，否则用 Java 字段名。 */
	private static String jsonNameOf(RecordComponent component) {
		JsonProperty annotation = component.getAnnotation(JsonProperty.class);
		if (annotation != null && !annotation.value().isBlank()) {
			return annotation.value();
		}
		return component.getName();
	}

	/**
	 * 取一个 record 组件上的约束注解。
	 *
	 * <h2>为什么必须绕到字段上去取（这是本类踩过的最贵的一个坑）</h2>
	 * 最直觉的写法是 {@code component.getAnnotations()} —— <b>它返回空数组</b>。
	 * 实测（JDK 21）：
	 * <pre>
	 *   record Params(@NotBlank String query, @Max(10) Integer topK) {}
	 *
	 *   component.getAnnotations()          -> []
	 *   component.getDeclaredAnnotations()  -> []
	 *   getDeclaredField("query").getAnnotations()    -> [@NotBlank]
	 *   component.getAccessor().getAnnotations()      -> [@NotBlank]
	 * </pre>
	 * 原因：{@code @NotBlank} 的 {@code @Target} 是 {@code {METHOD, FIELD, ANNOTATION_TYPE, ...}}，
	 * 而 <b>RECORD_COMPONENT 不在其中</b>。注解记录在 record 上时会被传播到
	 * 「隐式字段」与「访问器方法」，但<b>不会</b>落到 {@code RecordComponent} 本身。
	 * 而 JSR-380 的校验器走的是字段/访问器，所以它照常生效。
	 *
	 * <h2>这个坑的后果为什么特别严重</h2>
	 * 它是<b>单向的静默失效</b>：
	 * <ul>
	 *   <li>模型看到的 schema 里<b>没有任何约束</b>（没有 required、没有 maximum、没有 maxLength）——
	 *       于是它完全不知道边界，产出越界参数是必然的；</li>
	 *   <li>Java 侧<R>仍然严格执行</b>约束 —— 于是"模型按说明调用"却<b>永远过不去</b>。</li>
	 * </ul>
	 * 而表现是"模型老是传错参数"，排查方向会跑到模型能力和提示词上去。
	 * 我最初写这个类时的注释里正好预言了这个失败模式，然后自己原样踩了进去 ——
	 * <b>发现它的不是"我想清楚了"，而是一条断言"schema 里必须有 required"</b>。
	 * 这也是为什么下面 {@code requiredFields} 必须在测试里逐字段核对，而不是只看 schema 非空。
	 */
	private static Annotation[] constraintsOf(Class<?> parameterType, RecordComponent component, String toolName) {
		Annotation[] onComponent = component.getAnnotations();
		if (onComponent.length > 0) {
			// 万一将来 JDK 把 RECORD_COMPONENT 加进 @Target，这里也能直接用
			return onComponent;
		}
		try {
			Field field = parameterType.getDeclaredField(component.getName());
			return field.getAnnotations();
		}
		catch (NoSuchFieldException e) {
			// 读不到字段说明这不是普通 record（或者 reflection 被限制了）。
			// 抛而不是"返回空数组"：返回空会让上面那个静默失效重现 ——
			// 而且这次连"schema 少了约束"都看不出来，因为没有约束的 schema 也是合法的
			throw new UnsupportedToolParameterException(
					"工具「%s」的参数 %s.%s 上的约束注解读不到（%s）。".formatted(toolName, parameterType.getSimpleName(),
							component.getName(), e.getMessage())
							+ "**必须抛**：读不到注解就意味着 schema 里会缺约束（模型不知道），"
							+ "而校验器仍然会执行它们 —— 模型会「按说明调用」却永远过不去。");
		}
	}

	private static boolean isRequired(Annotation[] annotations, String field) {
		for (Annotation annotation : annotations) {
			if (annotation instanceof NotNull || annotation instanceof NotBlank || annotation instanceof NotEmpty) {
				return true;
			}
		}
		return false;
	}

	/** 类型 → {@code type} 关键字。认不出来的类型抛异常（不猜）。 */
	private static ObjectNode schemaFor(Class<?> type, java.lang.reflect.Type genericType, String toolName, String field) {
		ObjectNode node = MAPPER.createObjectNode();

		if (type == String.class || type == CharSequence.class) {
			node.put("type", "string");
			return node;
		}
		if (type == int.class || type == long.class || type == Integer.class || type == Long.class
				|| type == short.class || type == Short.class || type == byte.class || type == Byte.class
				|| type == java.math.BigInteger.class) {
			node.put("type", "integer");
			return node;
		}
		if (type == double.class || type == float.class || type == Double.class || type == Float.class
				|| type == BigDecimal.class) {
			node.put("type", "number");
			return node;
		}
		if (type == boolean.class || type == Boolean.class) {
			node.put("type", "boolean");
			return node;
		}
		if (type.isEnum()) {
			node.put("type", "string");
			ArrayNode values = node.putArray("enum");
			for (Object constant : type.getEnumConstants()) {
				values.add(constant.toString());
			}
			return node;
		}
		if (type.isArray()) {
			node.put("type", "array");
			node.set("items", schemaFor(type.getComponentType(), type.getComponentType(), toolName, field));
			return node;
		}
		if (List.class.isAssignableFrom(type) || java.util.Collection.class.isAssignableFrom(type)) {
			node.put("type", "array");
			Class<?> elementType = elementTypeOf(genericType);
			if (elementType == null) {
				// 裸 List（没有泛型）—— schema 只能说"数组"，元素类型未知。
				// 这里允许，但把 items 写成空 schema 而不是假装它是 string：
				// 假装成 string 会让模型把数组里的对象序列化成字符串传过来
				node.set("items", MAPPER.createObjectNode());
			}
			else {
				node.set("items", schemaFor(elementType, elementType, toolName, field));
			}
			return node;
		}
		if (type == java.util.Map.class || java.util.Map.class.isAssignableFrom(type)) {
			node.put("type", "object");
			return node;
		}
		if (type.isRecord()) {
			// 嵌套 record：递归展开成 object。深度很浅（工具参数不鼓励多层嵌套），
			// 所以不做环检测 —— 但如果真有人写出自引用的 record，这里会栈溢出，
			// 那是最容易发现的一种失败，不会静默
			ObjectNode nested = MAPPER.createObjectNode();
			nested.put("type", "object");
			ObjectNode props = nested.putObject("properties");
			ArrayNode nestedRequired = nested.putArray("required");
			List<RecordComponent> components = new ArrayList<>(List.of(type.getRecordComponents()));
			components.sort(Comparator.comparing(RecordComponent::getName));
			for (RecordComponent component : components) {
				String nestedName = jsonNameOf(component);
				ObjectNode propertySchema = schemaFor(component.getType(), component.getGenericType(), toolName, nestedName);
				Annotation[] nestedAnnotations = constraintsOf(type, component, toolName);
				applyConstraints(propertySchema, nestedAnnotations, component.getType(), toolName, nestedName);
				props.set(nestedName, propertySchema);
				if (isRequired(nestedAnnotations, nestedName)) {
					nestedRequired.add(nestedName);
				}
			}
			if (nestedRequired.isEmpty()) {
				nested.remove("required");
			}
			nested.put("additionalProperties", false);
			return nested;
		}

		throw new UnsupportedToolParameterException(
				"工具「%s」的参数字段「%s」类型是 %s，JsonSchemaGenerator 不知道该生成什么 schema。".formatted(toolName,
						field, type.getName())
						+ "支持的类型：String / 数值 / 布尔 / enum / List / 数组 / 嵌套 record / Map。"
						+ "**这里刻意不猜**：猜出一个错的 schema 会让模型按错格式调用，而这种失败看起来像模型能力问题。"
						+ "要么把字段改成受支持的类型，要么给 JsonSchemaGenerator 增加这个类型的映射。");
	}

	private static Class<?> elementTypeOf(java.lang.reflect.Type genericType) {
		if (genericType instanceof java.lang.reflect.ParameterizedType parameterized) {
			java.lang.reflect.Type[] args = parameterized.getActualTypeArguments();
			if (args.length == 1 && args[0] instanceof Class<?> clazz) {
				return clazz;
			}
		}
		return null;
	}

	/**
	 * 把 JSR-380 注解翻译成 JSON Schema 关键字。
	 *
	 * <p>扫到"认得出来但不支持"的注解直接抛 —— 理由见类注释，这是本类最重要的行为。
	 */
	private static void applyConstraints(ObjectNode node, Annotation[] annotations, Class<?> type, String toolName,
			String field) {
		for (Annotation annotation : annotations) {
			if (annotation instanceof NotNull) {
				continue; // 由 required 表达，已在调用方处理
			}
			if (annotation instanceof NotBlank) {
				node.put("minLength", 1);
				continue;
			}
			if (annotation instanceof NotEmpty) {
				if (isTextual(type)) {
					node.put("minLength", 1);
				}
				else {
					node.put("minItems", 1);
				}
				continue;
			}
			if (annotation instanceof Size size) {
				if (isTextual(type)) {
					if (size.min() > 0) {
						node.put("minLength", size.min());
					}
					if (size.max() < Integer.MAX_VALUE) {
						node.put("maxLength", size.max());
					}
				}
				else {
					if (size.min() > 0) {
						node.put("minItems", size.min());
					}
					if (size.max() < Integer.MAX_VALUE) {
						node.put("maxItems", size.max());
					}
				}
				continue;
			}
			if (annotation instanceof Min min) {
				node.put("minimum", min.value());
				continue;
			}
			if (annotation instanceof Max max) {
				node.put("maximum", max.value());
				continue;
			}
			if (annotation instanceof DecimalMin decimalMin) {
				node.put("minimum", new BigDecimal(decimalMin.value()));
				continue;
			}
			if (annotation instanceof DecimalMax decimalMax) {
				node.put("maximum", new BigDecimal(decimalMax.value()));
				continue;
			}
			if (annotation instanceof Pattern pattern) {
				node.put("pattern", pattern.regexp());
				continue;
			}
			if (annotation instanceof Null) {
				throw unsupported(toolName, field, "@Null",
						"工具参数出现「必须为 null」没有实际场景；如果确实要表达可选，用包装类型 + 不加 @NotNull");
			}
			// 剩下的只关心 jakarta.validation 命名空间下的注解：
			// 其它命名空间（Jackson / 自定义）不是约束，直接忽略
			if (annotation.annotationType().getName().startsWith("jakarta.validation.constraints.")) {
				throw unsupported(toolName, field, "@" + annotation.annotationType().getSimpleName(),
						"给 JsonSchemaGenerator 增加这个注解的映射，或把它从参数上拿掉并对模型明说");
			}
		}
	}

	private static boolean isTextual(Class<?> type) {
		return type == String.class || type == CharSequence.class;
	}

	private static UnsupportedToolParameterException unsupported(String toolName, String field, String annotation,
			String suggestion) {
		return new UnsupportedToolParameterException(
				"工具「%s」的参数字段「%s」用了不被 schema 生成器支持的约束 %s。".formatted(toolName, field, annotation)
						+ "**这里必须抛，不能忽略**：忽略之后 schema 里没有这条约束（模型不知道），"
						+ "而 Validator 仍然会执行它（会拒绝）—— 于是模型「按说明调用」却永远过不去，"
						+ "而工具说明看起来毫无问题。建议：" + suggestion);
	}

	/** 生成失败时抛的异常。名字里带 Unsupported 是为了让日志一眼看出"不是数据错，是能力缺口"。 */
	public static class UnsupportedToolParameterException extends RuntimeException {

		public UnsupportedToolParameterException(String message) {
			super(message);
		}
	}

}
