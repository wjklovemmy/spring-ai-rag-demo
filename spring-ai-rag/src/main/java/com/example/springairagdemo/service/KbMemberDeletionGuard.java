package com.example.springairagdemo.service;

import com.example.springairagdemo.entity.KbMemberEntity;
import com.example.user.security.ForbiddenException;
import com.example.user.spi.UserDeletionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户删除扩展点（RAG 侧实现）：
 * <ul>
 *   <li>{@link #validateDeletion}：若待删除用户是某知识库最后一个 OWNER，拒绝删除（需先转移所有权）</li>
 *   <li>{@link #onUserDeleted}：删除成功后清理该用户的全部知识库授权（kb_member）</li>
 * </ul>
 * 由用户域 {@code UserService.deleteUser} 自动触发，保持依赖方向单向（业务 → 用户域）。
 */
@Component
@RequiredArgsConstructor
public class KbMemberDeletionGuard implements UserDeletionGuard {

    private final KbMemberService kbMemberService;
    private final KbAuthorizationService kbAuthorizationService;

    @Override
    public void validateDeletion(Long userId) {
        List<Long> ownKbIds = kbMemberService.lambdaQuery()
                .eq(KbMemberEntity::getUserId, userId)
                .eq(KbMemberEntity::getRole, "OWNER")
                .list().stream()
                .map(KbMemberEntity::getKbId)
                .toList();
        for (Long kbId : ownKbIds) {
            if (kbAuthorizationService.isLastOwner(kbId, userId)) {
                throw new ForbiddenException("该用户是某个知识库的最后一个所有者，请先转移所有权后再删除");
            }
        }
    }

    @Override
    public void onUserDeleted(Long userId) {
        kbMemberService.lambdaUpdate()
                .eq(KbMemberEntity::getUserId, userId)
                .remove();
    }
}
