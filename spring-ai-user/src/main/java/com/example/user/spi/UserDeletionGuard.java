package com.example.user.spi;

/**
 * 用户删除扩展点（SPI）：
 * 用户域在删除用户前后调用，供依赖用户域的业务模块（如 RAG 知识库授权）实现，
 * 避免用户模块反向依赖业务模块，保持依赖方向单向（业务 → 用户域）。
 * <ul>
 *   <li>{@link #validateDeletion(Long)}：删除前校验（如“最后所有者保护”），不满足时抛异常阻止删除</li>
 *   <li>{@link #onUserDeleted(Long)}：删除成功后清理该用户的跨域数据（如知识库成员授权）</li>
 * </ul>
 * 宿主应用通过 {@code @Component} 提供实现类，Spring 自动收集注入。
 */
public interface UserDeletionGuard {

    /**
     * 删除前校验。
     *
     * @param userId 待删除用户 ID
     * @throws RuntimeException 不允许删除时抛出（如该用户是某知识库最后一个所有者）
     */
    void validateDeletion(Long userId);

    /**
     * 删除成功后清理该用户的跨域数据。
     *
     * @param userId 已删除用户 ID
     */
    void onUserDeleted(Long userId);
}
