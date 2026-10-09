package xyz.robinjoon.notionblog.domain.publication

import xyz.robinjoon.notionblog.domain.post.PostId

object PublicationPolicy {
    fun validateForActivation(
        revision: PublicationRevision,
        members: Collection<PublicationMember>,
        availabilityByPostId: Map<PostId, PostAvailability>,
        renderablePostIds: Set<PostId>,
    ) {
        require(revision.state == PublicationRevisionState.STAGING) {
            "only staging revisions can be activated"
        }
        require(members.isNotEmpty()) { "a publication revision must contain a root member" }
        require(members.all { it.revisionId == revision.id }) {
            "publication members must belong to the revision being activated"
        }

        val membersByPostId = members.associateBy(PublicationMember::postId)
        require(membersByPostId.size == members.size) { "a publication revision cannot contain duplicate members" }

        val roots = members.filter { it.parentPostId == null }
        require(roots.size == 1) { "a publication revision must contain exactly one root member" }
        require(roots.single().depth == 0) { "the root publication member must have depth zero" }

        members.forEach { member ->
            validateMember(member, membersByPostId, availabilityByPostId, renderablePostIds)
        }

        members.forEach { member -> verifyPathEndsAtRoot(member, membersByPostId, roots.single().postId) }
    }

    private fun validateMember(
        member: PublicationMember,
        membersByPostId: Map<PostId, PublicationMember>,
        availabilityByPostId: Map<PostId, PostAvailability>,
        renderablePostIds: Set<PostId>,
    ) {
        require(availabilityByPostId[member.postId]?.postId == member.postId) {
            "every publication member must have a confirmed availability"
        }
        if (member.parentPostId != null) {
            val parent =
                membersByPostId[member.parentPostId]
                    ?: throw IllegalArgumentException("a non-root publication member must have a parent in the same revision")
            require(member.depth == parent.depth + 1) {
                "a publication member depth must be one greater than its parent"
            }
        }
        if (availabilityByPostId.getValue(member.postId).status == PostAvailabilityStatus.PUBLISHED) {
            require(member.postId in renderablePostIds) {
                "published publication members must have a renderable snapshot"
            }
        }
    }

    private fun verifyPathEndsAtRoot(
        member: PublicationMember,
        membersByPostId: Map<PostId, PublicationMember>,
        rootPostId: PostId,
    ) {
        val visited = mutableSetOf<PostId>()
        var current = member
        while (current.parentPostId != null) {
            require(visited.add(current.postId)) { "a publication revision cannot contain cycles" }
            current = membersByPostId.getValue(current.parentPostId)
        }
        require(current.postId == rootPostId) { "every publication member must be connected to the root" }
    }
}
