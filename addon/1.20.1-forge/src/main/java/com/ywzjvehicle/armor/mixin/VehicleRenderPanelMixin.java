package com.ywzjvehicle.armor.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ywzjvehicle.armor.ArmorSystem;
import com.ywzjvehicle.armor.VehicleArmorMod;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Hides a used reactive panel's whole display bone tree, not just its root bone.
 *
 * <p>The display model's {@code turret_era} / {@code turret_era2} bones carry the ERA tiles,
 * but the louver plates are child bones ({@code tri_3..26}, {@code tri_35..46}).  The base's
 * {@code applyDetachedPart} flips only the named bone's {@code visible} flag, which leaves
 * the children on screen.  This runs at the start of the vehicle render and walks the whole
 * tree: a panel whose part is destroyed (or detached) hides root and descendants; a repaired
 * part restores them.
 *
 * <p>The simplebedrockmodel types are a Jar-in-Jar dependency of the base mod, so reflection
 * is used rather than naming them in this addon's compile classpath.  The methods and field
 * are resolved once from {@code BakedModelInstance} / {@code BoneState}.
 */
@Mixin(VehicleRender.class)
public abstract class VehicleRenderPanelMixin {

    /** Part id + health + hide state, so one line is logged per transition instead of per frame. */
    private static final Set<String> LOGGED = new HashSet<>();
    private static final Set<String> LISTED = new HashSet<>();

    private static Method getBoneIndexes;
    private static Method nameMethod;
    private static Method childrenMethod;
    private static Field visibleField;
    private static Field xScaleField;
    private static Field yScaleField;
    private static Field zScaleField;

    // Just before the display model is drawn, so the visibility set here is the last word:
    // applyAnimationPose runs after applyDetachedPart and resets every bone's visible flag
    // through BoneState.reset(), which would undo a HEAD-time hide of the children.
    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/client/resource/vehicle/VehicleBedrockModel;"
                            + "renderToBuffer(Lcom/github/mcmodderanchor/simplebedrockmodel/v2/common/"
                            + "model/runtime/BakedModelInstance;Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/MultiBufferSource;"
                            + "Lnet/minecraft/resources/ResourceLocation;I)V"))
    private void ywzjarmor$hideDestroyedPanelTrees(AbstractVehicle vehicle, float entityYaw,
                                                    float partialTick, PoseStack poseStack,
                                                    MultiBufferSource bufferSource, int packedLight,
                                                    CallbackInfo ci) {
        Object vehicleModel = invoke(vehicle, "getVehicleModelInstance");
        Object cabinModel = invoke(vehicle, "getCabinModelInstance");
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            String boneName = part.getRenderBoneName();
            if (boneName == null || boneName.isBlank()) {
                continue;
            }
            // Mirror the base's own rule, not "show everything that is not a panel".  A part
            // that renders its own bone (SuspensionUnit, WeaponUnit, ...) must leave the copy in
            // the vehicle model hidden; forcing it visible draws a second copy at the rest
            // position while the part draws the moving one - the K2's doubled road wheel.
            boolean selfRendered = part.rendersBone();
            boolean panelDestroyed = part.isDestroyed()
                    && ArmorSystem.isReactivePanel(vehicle, part.getId());
            boolean hide = part.isDetached() || selfRendered || panelDestroyed;
            // A self-rendered bone is hidden from the vehicle pass but must keep scale 1: the
            // part's own renderPart() flips `visible` back on and draws that same BoneState, so
            // collapsing the scale would erase the part instead of just the duplicate.
            boolean collapse = hide && !(selfRendered && !part.isDetached() && !panelDestroyed);
            int hidden = setTree(vehicleModel, boneName, !hide, collapse);
            int hiddenCabin = setTree(cabinModel, boneName, !hide, collapse);
            if (ArmorSystem.isReactivePanel(vehicle, part.getId())) {
                String key = vehicle.getVehicleId() + ":" + part.getId() + ":"
                        + part.getHealth() + ":" + hide;
                if (LOGGED.add(key)) {
                    VehicleArmorMod.LOGGER.info(
                            "[Vehicle Armor] render {} health={} bone={} hide={} bones={}/{} "
                                    + "(vehicle model {})",
                            part.getId(), part.getHealth(), boneName, hide,
                            hidden, hiddenCabin, vehicleModel != null);
                }
            }
        }
    }

    private static Object invoke(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * @return the number of bone states set, {@code -1} when the model has no bone of that name,
     *         {@code -2} when there is no model for this view, {@code -3} when reflection failed.
     */
    private static int setTree(Object model, String boneName, boolean visible, boolean collapse) {
        if (model == null) {
            return -2;
        }
        try {
            ensure(model.getClass());
            Object[] bones = (Object[]) getBoneIndexes.invoke(model);
            if (bones.length == 0) {
                return -1;
            }
            if (nameMethod == null) {
                nameMethod = bones[0].getClass().getMethod("name");
            }
            String lower = boneName.toLowerCase(java.util.Locale.ROOT);
            java.util.regex.Pattern word = java.util.regex.Pattern.compile(
                    "(?<![a-z0-9])" + java.util.regex.Pattern.quote(lower) + "(?![a-z0-9])",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
            Object exact = null;
            Object partial = null;
            for (Object bone : bones) {
                String name = String.valueOf(nameMethod.invoke(bone));
                if (boneName.equals(name)) {
                    exact = bone;
                    break;
                }
                if (partial == null && word.matcher(name).find()) {
                    partial = bone;
                }
            }
            if (exact != null) {
                return setBone(bones, exact, visible, collapse);
            }
            if (partial != null) {
                VehicleArmorMod.LOGGER.info(
                        "[Vehicle Armor] display bone {} matched partially as {}",
                        boneName, nameMethod.invoke(partial));
                return setBone(bones, partial, visible, collapse);
            }
            if (LISTED.add(boneName)) {
                StringBuilder era = new StringBuilder();
                int count = 0;
                for (Object bone : bones) {
                    String name = String.valueOf(nameMethod.invoke(bone));
                    if (name.toLowerCase(java.util.Locale.ROOT).contains("era")
                            || name.toLowerCase(java.util.Locale.ROOT).contains("hull")
                            || name.toLowerCase(java.util.Locale.ROOT).contains("turret")) {
                        era.append(name).append(' ');
                    }
                    count++;
                }
                VehicleArmorMod.LOGGER.info(
                        "[Vehicle Armor] display bone {} absent ({} bones); armour bones: {}",
                        boneName, count, era);
            }
            return -1;
        } catch (ReflectiveOperationException e) {
            return -3;
        }
    }

    private static int setBone(Object[] bones, Object bone, boolean visible, boolean collapse)
            throws ReflectiveOperationException {
        if (visibleField == null) {
            Class<?> boneClass = bone.getClass();
            visibleField = boneClass.getField("visible");
            xScaleField = boneClass.getField("xScale");
            yScaleField = boneClass.getField("yScale");
            zScaleField = boneClass.getField("zScale");
            childrenMethod = boneClass.getMethod("children");
        }
        visibleField.setBoolean(bone, visible);
        // Zero scale is the belt to visible's braces: the base's display pipeline has been
        // seen to re-show a bone after the animation pose, but a collapsed bone has no area
        // to draw whatever the flag says.  Children inherit the parent's scale as well.
        // A self-rendered bone (a suspension wheel) is hidden only from the vehicle pass and
        // keeps scale 1 - renderPart() re-enables `visible` and draws this same BoneState.
        float scale = !visible && collapse ? 0.0f : 1.0f;
        xScaleField.setFloat(bone, scale);
        yScaleField.setFloat(bone, scale);
        zScaleField.setFloat(bone, scale);
        int count = 1;
        for (int child : (int[]) childrenMethod.invoke(bone)) {
            if (child >= 0 && child < bones.length) {
                count += setBone(bones, bones[child], visible, collapse);
            }
        }
        return count;
    }

    /** Resolves the members once, from the concrete model class (no library types needed). */
    private static void ensure(Class<?> modelClass) throws ReflectiveOperationException {
        if (getBoneIndexes != null) {
            return;
        }
        getBoneIndexes = modelClass.getMethod("getBoneIndexes");
    }
}
