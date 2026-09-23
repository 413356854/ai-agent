package ai.agent.controller;

import ai.agent.entity.agent.skill.AgentSkill;
import ai.agent.service.skill.SkillConflictException;
import ai.agent.service.skill.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>技能管理的界面入口</b>（应用管理区 · skill管理）。
 *
 * <h2>端点与它回答的问题</h2>
 * <table border="1">
 *   <caption>四个端点</caption>
 *   <tr><th>端点</th><th>回答什么</th></tr>
 *   <tr><td>{@code GET /api/skills}</td><td>"现在有哪些技能"（{@code ?userId=} 时只给<b>这个人拥有的</b>那些）+ {@code ?userId=} 时他能选的文件夹</td></tr>
 *   <tr><td>{@code POST /api/skills?userId=}</td><td>"新建一条技能"（拥有者与最后修改人都写这个 userId）</td></tr>
 *   <tr><td>{@code PUT /api/skills/{id}?userId=}</td><td>"改这一条的内容"（只有它的拥有者能改）</td></tr>
 *   <tr><td>{@code DELETE /api/skills/{id}?userId=}</td><td>"删这一条"（只有它的拥有者能删）</td></tr>
 * </table>
 *
 * <h2>★ 当前登录人走查询参数，不走请求体</h2>
 * {@code userId} 是"我是谁"，它在三个写端点上都是查询参数：{@code DELETE} 没有请求体，
 * 而把同一个东西在 POST / PUT 里放请求体、在 DELETE 里放查询参数，
 * 会让"这个字段从哪儿读"每次都要看一眼。四个端点统一成查询参数之后只有一种读法。
 *
 * <p>它是<b>必填</b>（缺了 400）：这一层没有登录链路（见下面一段），
 * 而"没传就当作未登录放行"的那个分支在界面上与正常路径长得一样 ——
 * 唯一的差别是写进去的归属是错的。
 *
 * <h2>★ 读是公开的，写要登录</h2>
 * 未登录时 {@code GET} 返回<b>全部</b>记录、{@code folders} 为空（文件夹是"这个人的角色给的"，
 * 没有他就没有这一份），而三个写端点一律 400 并说明"请先在页头模拟登录里选一个人"。
 * 这条分界是刻意的：它让"这一页有别人的技能、而我只能动自己的"在界面上直接看得出来
 * （列表上有别人的行、按钮是禁用的），而不是把别人的记录藏起来。
 *
 * <p>⚠️ 与 {@code PermissionController} 同一条现状：这一族端点<b>没有真正的登录</b>，
 * 能访问这个端口的人可以传任意 {@code userId}。所以 {@code userId} 是<b>归属判据</b>，
 * 不是一道安全边界 —— 把它变成安全边界需要先接登录链路（那一件在权限管理那边也还挂着）。
 *
 * <h2>为什么写操作返回的是"改完之后的那一份"</h2>
 * 与 {@code PermissionController} 同一条取舍：界面拿到 {@code {"ok":true}} 之后只能重新拉一次全量，
 * 而那次拉取与这次写入之间可能隔着别人的一次修改 —— 于是页面上显示的是"别人改完之后的样子"，
 * 而用户以为是自己这次的结果。返回新状态就没有这个缝（含 id 与两个时间）。
 *
 * <h2>为什么错误码分开：400 / 409</h2>
 * 两种失败的处置完全不同：400 是"这句请求缺了什么或越界了"（改了重试），
 * 409 是"同一个文件夹下这个名字已经有了"（换个名字，或者去改那一条）。
 * 混成一个码会让用户对着重名一遍遍点保存。
 */
@RestController
@RequestMapping(path = "/api/skills", produces = MediaType.APPLICATION_JSON_VALUE)
public class SkillController {

	private static final Logger log = LoggerFactory.getLogger(SkillController.class);

	private final SkillService skills;

	public SkillController(SkillService skills) {
		this.skills = skills;
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	/**
	 * 技能页要的一切，一次取回。
	 *
	 * @param userId 当前登录的人（{@code ?userId=<用户 id>}）。<b>不传就是不筛</b>（全部记录，
	 *        且文件夹选项为空）—— 传了但这个人不在账号表里是 <b>400</b>（不是"筛不出来"：
	 *        一个不在账号表里的 id 是"这次登录已经失效了"，界面该提示重新登录）
	 */
	@GetMapping
	public Map<String, Object> snapshot(@RequestParam(name = "userId", required = false) Long userId) {
		SkillService.Snapshot snapshot = skills.snapshot(userId);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (AgentSkill skill : snapshot.skills()) {
			rows.add(skill(skill));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("skills", rows);
		body.put("total", rows.size());
		// 回显这次到底筛没筛、按谁筛：界面上那句"只看我拥有的"依据就是它（前端不自己推一份）。
		// ⚠️ 键是 userId 而不是 ownerId —— 后者是每一行记录上的归属，两个同名的键放进同一个响应体里迟早被读错
		body.put("userId", snapshot.userId());
		body.put("userName", snapshot.userName());
		body.put("ownershipJudged", snapshot.ownershipJudged());
		body.put("folders", snapshot.folders());
		body.put("howToWrite", "POST /api/skills?userId=<当前登录的人>；改 PUT /api/skills/{id}?userId=…；"
				+ "删 DELETE /api/skills/{id}?userId=…");
		return body;
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> create(@RequestParam(name = "userId", required = false) Long userId,
			@RequestBody SkillRequest request) {
		if (request == null || request.name() == null || request.name().isBlank()) {
			return badRequest("缺少 name（技能名）");
		}
		// folder / description / content 的判据都在服务层：folder 要对着这个人的选项集判，
		// 而那份选项集只有服务层查得到（这一层再判一次就是第二份判据）
		AgentSkill created = skills.create(userId, request.folder(), request.name(), request.description(),
				request.content());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("skill", skill(created));
		return ResponseEntity.ok(body);
	}

	/**
	 * 改一条技能的内容。
	 *
	 * <p>{@code description} / {@code content} 的两条空语义<b>不同</b>，别弄反：
	 * 字段缺席（{@code null}）= 不动这一列，空串 = 清空这一列。
	 * 这正是界面需要的："内容删光再保存"是一次明确的清空，而"这次只想改个说明"
	 * 不该把正文一起抹掉。判据在服务层，这里只负责把 null 与空串原样传下去。
	 */
	@PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> update(@PathVariable Long id,
			@RequestParam(name = "userId", required = false) Long userId, @RequestBody SkillRequest request) {
		AgentSkill updated = skills.update(userId, id, request == null ? null : request.folder(),
				request == null ? null : request.name(), request == null ? null : request.description(),
				request == null ? null : request.content());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("skill", skill(updated));
		return body;
	}

	/**
	 * 删一条技能。响应里带回被删掉的那一份 —— 界面据此说"已删除《名字》"，
	 * 而不是一句没有主语的"删除成功"（列表上少了一行时，人得知道少的是哪一行）。
	 */
	@DeleteMapping("/{id}")
	public Map<String, Object> delete(@PathVariable Long id,
			@RequestParam(name = "userId", required = false) Long userId) {
		// 服务层把被删掉的那一份带回来：删完之后"删的是哪一条"就查不到了
		// （与 PermissionController#deleteRole 在删除前数 affectedAccounts 同一条理由）
		AgentSkill removed = skills.delete(userId, id);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("skillId", id);
		body.put("skill", skill(removed));
		return body;
	}

	// ------------------------------------------------------------------
	//  错误处理
	// ------------------------------------------------------------------

	/**
	 * 撞唯一约束 → 409。
	 *
	 * <p>它不能是 500：两个标签页同时点"新建"不是服务器故障，
	 * 而界面要能给出"换一个名字，或者去改那一条"这句话 —— 那需要它知道自己撞的是唯一约束。
	 */
	@ExceptionHandler(SkillConflictException.class)
	public ResponseEntity<Map<String, Object>> conflict(SkillConflictException e) {
		log.info("技能写入冲突：{}", e.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("ok", false, "error", String.valueOf(e.getMessage())));
	}

	/**
	 * 业务层的 {@code ResponseStatusException}（400 那一族）自带 HTTP 状态，
	 * 会由 {@code ErrorResponseAdvice} 处理成标准错误体 —— 这里不再接一遍。
	 * 接两遍的后果是同一个错误有两种响应体形状，而界面只能按其中一种解析。
	 */

	private static ResponseEntity<Map<String, Object>> badRequest(String message) {
		return ResponseEntity.badRequest().body(Map.of("ok", false, "error", message));
	}

	// ------------------------------------------------------------------
	//  装配
	// ------------------------------------------------------------------

	/**
	 * 一条技能。
	 *
	 * <p>{@code ownerName} 与 {@code lastModifiedByName} 用的是读侧 JOIN 出来的那一份
	 * （{@code AgentSkill#displayOwner} 在账号行不在时才退回 id）——
	 * 界面拿到的永远是"能显示的一串"，不必自己判空。
	 *
	 * <p>{@code content} 为 null 时给空串、{@code description} 同理：这两个字段在库里可空，
	 * 而界面上的编辑框要的是字符串。{@code updatedAt} 用 ISO-8601 文本 ——
	 * 与 {@code Instant.toString()} 一致，前端直接显示。
	 */
	private static Map<String, Object> skill(AgentSkill skill) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", skill.id());
		row.put("folder", skill.folder());
		row.put("name", skill.name());
		row.put("description", skill.description() == null ? "" : skill.description());
		row.put("content", skill.content() == null ? "" : skill.content());
		row.put("ownerId", skill.ownerId());
		row.put("ownerName", skill.displayOwner());
		row.put("lastModifiedBy", skill.lastModifiedBy());
		row.put("lastModifiedByName", skill.displayLastModifiedBy());
		row.put("createdAt", skill.createdAt() == null ? "" : skill.createdAt().toString());
		row.put("updatedAt", skill.updatedAt() == null ? "" : skill.updatedAt().toString());
		return row;
	}

	// ------------------------------------------------------------------
	//  请求体
	// ------------------------------------------------------------------

	/**
	 * 建 / 改技能的请求体。
	 *
	 * <p><b>刻意没有 ownerId 与 lastModifiedBy</b>：那两个字段由服务端按当前登录人填。
	 * 放进请求体就等于把归属交给客户端 —— 而它错了的时候，界面上一切正常。
	 *
	 * @param folder 文件夹目录（只能是当前登录人角色目录前缀里的一个；新增时必填）
	 * @param name 技能名（新增时必填）
	 * @param description 这个技能是干什么的。{@code null}=不改这一列，空串=清空
	 * @param content 技能正文。{@code null}=不改这一列，空串=清空
	 */
	public record SkillRequest(String folder, String name, String description, String content) {
	}

}
