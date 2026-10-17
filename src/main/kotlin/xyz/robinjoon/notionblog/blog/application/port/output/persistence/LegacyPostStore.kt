package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.application.model.LegacyPostRecord

// 전환 이전부터 남아 있는 게시 트리 테이블을 읽는다. 여기서는 그 테이블에 쓰지 않는다.
interface LegacyPostStore {
    // 아직 게시 상태가 없는 원본 연결 글을 반환한다. 레거시 공개 여부는 레거시 읽기 경로와 똑같이 활성 멤버, PUBLISHED
    // availability, 저장된 스냅숏 조건으로 계산한다.
    fun findUnmanaged(): List<LegacyPostRecord>
}
