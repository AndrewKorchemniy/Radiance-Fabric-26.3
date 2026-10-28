package com.radiance.client.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceMaintenance4Features;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderDemoteToHelperInvocationFeatures;

/**
 * The Vulkan device features Radiance needs on top of vanilla's, expressed as vanilla
 * {@link FeatureSet}s so they are enabled on the device vanilla creates.
 *
 * <p>Vanilla enables a {@link FeatureSet} only if every extension and feature in it is supported, so
 * {@link #RAY_TRACING_CORE} holds only what MCVR cannot run without, and everything MCVR uses "if
 * supported" gets a set of its own. Ray tracing GPUs that lack one of those still get the rest.
 *
 * <p>Vanilla always chains {@code VkPhysicalDeviceVulkan12Features}, so Vulkan 1.2 features must go
 * through {@link VulkanFeatureSets#VK12_FEATURES_STRUCT} rather than their per-extension structs, or
 * device creation fails validation. Vanilla also chains the separate synchronization2 and dynamic
 * rendering structs, so {@code VkPhysicalDeviceVulkan13Features} must never be added; 1.3 features use
 * their individual structs instead.
 */
public final class RadianceVulkanFeatures {

    private static final VulkanPNextStruct VK10 = VulkanFeatureSets.VK10_FEATURES_STRUCT;
    private static final VulkanPNextStruct VK11 = VulkanFeatureSets.VK11_FEATURES_STRUCT;
    private static final VulkanPNextStruct VK12 = VulkanFeatureSets.VK12_FEATURES_STRUCT;
    private static final VulkanPNextStruct ACCELERATION_STRUCTURE =
        new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class);
    private static final VulkanPNextStruct RAY_TRACING_PIPELINE =
        new VulkanPNextStruct(VkPhysicalDeviceRayTracingPipelineFeaturesKHR.class);
    private static final VulkanPNextStruct MAINTENANCE_4 =
        new VulkanPNextStruct(VkPhysicalDeviceMaintenance4Features.class);
    private static final VulkanPNextStruct DEMOTE_TO_HELPER_INVOCATION =
        new VulkanPNextStruct(VkPhysicalDeviceShaderDemoteToHelperInvocationFeatures.class);
    private static final VulkanPNextStruct INVOCATION_REORDER_EXT =
        new VulkanPNextStruct(VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.class);
    private static final VulkanPNextStruct INVOCATION_REORDER_NV =
        new VulkanPNextStruct(VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV.class);

    /** Without this set Radiance cannot render at all. */
    public static final FeatureSet RAY_TRACING_CORE = new FeatureSet(
        "Radiance ray tracing",
        Set.of("VK_KHR_acceleration_structure", "VK_KHR_ray_tracing_pipeline",
            "VK_KHR_deferred_host_operations"),
        Set.of(
            new VulkanFeature(ACCELERATION_STRUCTURE, "accelerationStructure"),
            new VulkanFeature(RAY_TRACING_PIPELINE, "rayTracingPipeline"),
            new VulkanFeature(VK12, "bufferDeviceAddress"),
            new VulkanFeature(VK12, "descriptorIndexing"),
            new VulkanFeature(VK12, "runtimeDescriptorArray"),
            new VulkanFeature(VK12, "descriptorBindingPartiallyBound"),
            new VulkanFeature(VK12, "descriptorBindingVariableDescriptorCount"),
            new VulkanFeature(VK12, "shaderSampledImageArrayNonUniformIndexing")
        )
    );

    public static final FeatureSet INVOCATION_REORDER_EXT_SET = new FeatureSet(
        "Radiance invocation reorder (EXT)",
        Set.of("VK_EXT_ray_tracing_invocation_reorder"),
        Set.of(new VulkanFeature(INVOCATION_REORDER_EXT, "rayTracingInvocationReorder"))
    );

    public static final FeatureSet INVOCATION_REORDER_NV_SET = new FeatureSet(
        "Radiance invocation reorder (NV)",
        Set.of("VK_NV_ray_tracing_invocation_reorder"),
        Set.of(new VulkanFeature(INVOCATION_REORDER_NV, "rayTracingInvocationReorder")),
        device -> !INVOCATION_REORDER_EXT_SET.isSupported(device)
    );

    private static final List<FeatureSet> OPTIONAL;

    static {
        List<FeatureSet> optional = new ArrayList<>();
        // Each of these is enabled by MCVR only when the device supports it, so each is its own set.
        for (String name : List.of("independentBlend", "shaderClipDistance", "shaderCullDistance",
            "logicOp", "depthBiasClamp", "shaderInt64", "shaderFloat64", "shaderInt16",
            "shaderStorageImageExtendedFormats", "shaderStorageImageReadWithoutFormat",
            "shaderStorageImageWriteWithoutFormat", "fragmentStoresAndAtomics")) {
            optional.add(single(VK10, name));
        }
        optional.add(single(VK11, "storageBuffer16BitAccess"));
        for (String name : List.of("descriptorBindingUpdateUnusedWhilePending",
            "descriptorBindingUniformBufferUpdateAfterBind",
            "descriptorBindingSampledImageUpdateAfterBind",
            "descriptorBindingStorageImageUpdateAfterBind",
            "descriptorBindingStorageBufferUpdateAfterBind", "shaderFloat16",
            "shaderBufferInt64Atomics", "shaderStorageBufferArrayNonUniformIndexing",
            "shaderStorageImageArrayNonUniformIndexing",
            "shaderUniformBufferArrayNonUniformIndexing")) {
            optional.add(single(VK12, name));
        }
        optional.add(single(ACCELERATION_STRUCTURE,
            "descriptorBindingAccelerationStructureUpdateAfterBind"));
        optional.add(single(RAY_TRACING_PIPELINE, "rayTracingPipelineTraceRaysIndirect"));
        optional.add(single(RAY_TRACING_PIPELINE, "rayTraversalPrimitiveCulling"));
        optional.add(new FeatureSet("Radiance maintenance4", Set.of("VK_KHR_maintenance4"),
            Set.of(new VulkanFeature(MAINTENANCE_4, "maintenance4"))));
        optional.add(new FeatureSet("Radiance demote to helper invocation",
            Set.of("VK_EXT_shader_demote_to_helper_invocation"),
            Set.of(new VulkanFeature(DEMOTE_TO_HELPER_INVOCATION, "shaderDemoteToHelperInvocation"))));
        optional.add(INVOCATION_REORDER_EXT_SET);
        optional.add(INVOCATION_REORDER_NV_SET);
        optional.add(new FeatureSet("Radiance memory budget", Set.of("VK_EXT_memory_budget"), Set.of()));
        // FSR frame generation's compatibility check requires this extension to be enabled.
        optional.add(new FeatureSet("Radiance extended dynamic state 3",
            Set.of("VK_EXT_extended_dynamic_state3"), Set.of()));
        optional.add(new FeatureSet("Radiance AMD coherent memory",
            Set.of("VK_AMD_device_coherent_memory"), Set.of()));
        OPTIONAL = Collections.unmodifiableList(optional);
    }

    private static volatile FeatureSet enabled;

    private RadianceVulkanFeatures() {
    }

    private static FeatureSet single(VulkanPNextStruct struct, String feature) {
        return new FeatureSet("Radiance " + feature, Set.of(), Set.of(new VulkanFeature(struct, feature)));
    }

    /** All Radiance sets, registered as optional so GPUs without ray tracing still start on Vulkan. */
    public static List<FeatureSet> featureSets() {
        List<FeatureSet> sets = new ArrayList<>(OPTIONAL.size() + 1);
        sets.add(RAY_TRACING_CORE);
        sets.addAll(OPTIONAL);
        // TODO(M3): add the DLSS, FSR and XeSS device extension sets, which their SDKs report at
        // runtime and so have to be queried from native before the device is created.
        return sets;
    }

    /** Records the feature set vanilla is about to create the device with. */
    public static void recordEnabled(FeatureSet enabledFeatures) {
        enabled = enabledFeatures;
    }

    public static boolean isRayTracingSupported() {
        FeatureSet current = enabled;
        return current != null && current.contains(RAY_TRACING_CORE);
    }

    /** The device extensions vanilla enabled, which native needs to know which code paths to use. */
    public static String[] enabledDeviceExtensions() {
        FeatureSet current = enabled;
        if (current == null) {
            throw new IllegalStateException("The Vulkan device has not been created yet");
        }
        return new LinkedHashSet<>(current.extensions()).toArray(String[]::new);
    }
}
