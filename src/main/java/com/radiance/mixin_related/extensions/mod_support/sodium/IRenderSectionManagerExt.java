package com.radiance.mixin_related.extensions.mod_support.sodium;

/** Radiance's additions to Sodium's {@code RenderSectionManager} (see {@code RenderSectionManagerMixins}). */
public interface IRenderSectionManagerExt {

    /**
     * Brings Radiance's copy of a section up to date after the section entered Radiance's window
     * of native slots: empty sections are reported empty, built ones are rebuilt (their previous
     * mesh was dropped or never captured). Main thread only.
     *
     * @param waitForBuild keep the request alive (return false) while the section has not been
     *                     built yet, instead of leaving it to its pending first build
     * @return true if nothing is left to do for this section
     */
    boolean radiance$resync(int sectionX, int sectionY, int sectionZ, boolean waitForBuild);
}
