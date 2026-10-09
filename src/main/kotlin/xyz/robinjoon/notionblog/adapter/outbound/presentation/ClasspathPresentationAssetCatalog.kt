package xyz.robinjoon.notionblog.adapter.outbound.presentation

import xyz.robinjoon.notionblog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.application.model.ResolvedPresentationAsset
import xyz.robinjoon.notionblog.application.port.output.presentation.PresentationAssetCatalog
import xyz.robinjoon.notionblog.domain.site.PresentationAssetRef

class ClasspathPresentationAssetCatalog(
    registry: Map<PresentationAssetRef, PresentationAssetDescriptor>,
    currentReferences: Map<String, PresentationAssetRef> = emptyMap(),
) : PresentationAssetCatalog {
    private val registeredAssets: Map<PresentationAssetRef, PresentationAssetDescriptor> =
        registry.toMap().also { assets ->
            assets.forEach { (reference, descriptor) -> validate(reference, descriptor) }
        }
    private val currentAssets: Map<String, PresentationAssetRef> =
        currentReferences.toMap().also { references ->
            references.forEach { (key, reference) -> validateCurrentReference(key, reference) }
        }

    override fun resolve(reference: PresentationAssetRef): PresentationAssetDescriptor? = registeredAssets[reference]

    override fun resolveCurrent(key: String): ResolvedPresentationAsset? {
        val reference = currentAssets[key] ?: return null
        val descriptor = registeredAssets[reference] ?: return null
        return ResolvedPresentationAsset(reference, descriptor)
    }

    private fun validate(
        reference: PresentationAssetRef,
        descriptor: PresentationAssetDescriptor,
    ) {
        require(descriptor.publicPath.startsWith("/") && !descriptor.publicPath.startsWith("//")) {
            "presentation asset public path must start with exactly one slash"
        }
        require(descriptor.mediaType in supportedMediaTypes) {
            "unsupported presentation asset media type: ${descriptor.mediaType}"
        }
        require(descriptor.integrity.isNotBlank()) {
            "presentation asset descriptor integrity must not be blank"
        }
        require(descriptor.integrity == reference.integrity) {
            "presentation asset descriptor integrity must match its reference"
        }
    }

    private fun validateCurrentReference(
        key: String,
        reference: PresentationAssetRef,
    ) {
        require(key == reference.key) {
            "presentation asset current reference key must match its map key"
        }
        require(registeredAssets.containsKey(reference)) {
            "presentation asset current reference must exist in the exact registry"
        }
    }

    private companion object {
        val supportedMediaTypes =
            setOf(
                "text/css",
                "text/javascript",
                "application/javascript",
            )
    }
}
