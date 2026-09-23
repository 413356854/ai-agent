package ai.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code agent_role_dir} 的一个<b>按账号</b>的读法：这个人全部角色的目录前缀。
 *
 * <p>它服务的是界面上的「文件夹目录」选项集（skill 管理那一页）：
 * 登录人 → 他的角色 → 这些角色的目录前缀。
 * 权限页的快照读的是 {@code RoleDirectoryMapper#all()}（全部角色的目录，一次取回），
 * 那一条回答不了"这个人的是哪些"。
 *
 * <h2>为什么放在这里而不是并进 RoleDirectoryMapper</h2>
 * 那个接口的注释写明它"只负责存取事实、库里不做前缀匹配"，加一条两表 JOIN 会让
 * "这个接口读的是哪张表"变得含糊。这个方法读的是 {@code agent_user_role ⋈ agent_role_dir}，
 * 归到哪一边都是半张表，所以单开一个接口、名字把两张表都写出来。
 *
 * <h2>不去重，因为去重会丢掉"这是哪几个角色给的"</h2>
 * 同一个人两个角色都授权了同一个目录前缀时，这里会返回两行 ——
 * 而界面上的选项集按前缀去重（一个文件夹出现两次点起来是一样的）。
 * 两件事分开：SQL 给事实，去重是展示口径。
 */
@Mapper
public interface UserDirectoryMapper {

	/**
	 * 一个账号全部角色的目录前缀（一个角色一行，可能重复）。
	 *
	 * <p>账号不存在、或一个角色都没有时返回空列表 —— 那正是"他没有任何文件夹可选"，
	 * 而界面上必须把这件事说出来（一个空的选项集看起来像"这一页坏了"）。
	 *
	 * <p>{@code INNER JOIN}（不是 LEFT JOIN）：这里要的是"角色给的目录"，
	 * 而一个没有任何目录的角色对这个问题没有答案。若是 LEFT JOIN，
	 * 那个角色会以一行 {@code dir_prefix = NULL} 的形式出现，
	 * 调用方还得再判一次空 —— 而漏判的表现是选项集里多出一个空文件夹。
	 */
	List<String> folderPrefixesOfUser(@Param("userId") Long userId);

}
