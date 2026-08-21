package com.example.springairagdemo.service;

import com.example.springairagdemo.entity.KbMemberEntity;
import com.example.springairagdemo.security.ForbiddenException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库成员删除防护（RAG 侧）：
 * <ul>
 *   <li>{@link #validateDeletion}：若待删除用户是某知识库最后一个 OWNER，拒绝删除（需先转移所有权）</li>
 *   <li>{@link #onUserDeleted}：删除成功后清理该用户的全部知识库授权（kb_member）</li>
 * </ul>
 * 用户服务独立部署后，由本服务 {@code /internal/kb/**} 内部接口驱动（经 InternalController），
 * 替代拆分前的用户域 SPI 扩展点调用。
 */
@Component
public class KbMemberDeletionGuard {

    private final KbMemberService kbMemberService;
    private final KbAuthorizationService kbAuthorizationService;

    public KbMemberDeletionGuard(KbMemberService kbMemberService,
                                 @Lazy KbAuthorizationService kbAuthorizationService) {
        this.kbMemberService = kbMemberService;
        this.kbAuthorizationService = kbAuthorizationService;
    }

    /** 删除前校验：待删除用户是某知识库最后一个 OWNER 时抛异常阻止删除 */
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

    /** 删除成功后清理该用户的全部知识库授权（kb_member） */
    public void onUserDeleted(Long userId) {
        kbMemberService.lambdaUpdate()
                .eq(KbMemberEntity::getUserId, userId)
                .remove();
    }
}
