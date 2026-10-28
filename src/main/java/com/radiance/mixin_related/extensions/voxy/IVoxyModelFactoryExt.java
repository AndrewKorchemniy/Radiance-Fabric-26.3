package com.radiance.mixin_related.extensions.voxy;

public interface IVoxyModelFactoryExt {
    /**
     * Takes the next finished bake from Voxy's upload queue, which nothing else drains while
     * Voxy's OpenGL renderer is off. The result is a {@link IVoxyModelUploadExt} or a
     * {@link IVoxyBiomeUploadExt}, or null when the queue is empty.
     */
    Object radiance$pollUpload();
}
