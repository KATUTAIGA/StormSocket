package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;

import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;
import gregtech.api.recipes.ingredients.GTRecipeInput;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * GregTech CEu（GTQT 追加分も同じレジストリに載る）の全レシピマップを
 * {@link QuestRecipeResolver.ProcRecipe} に変換する。GregTech が読み込まれている時しか
 * このクラスには触れない（{@code Loader.isModLoaded("gregtech")} で分岐済み）。
 *
 * <p>機械そのもの・電力・電圧Tierは要求しない「仮想加工」。確率出力（chanced output）は
 * 当てにしない（確実に出る出力だけを使う）。プログラム回路・型・レンズ等の
 * 非消費入力は機械側の設定とみなす。</p>
 */
final class GTRecipeSource {

    private GTRecipeSource() {
    }

    static List<Object> listMaps() {
        List<Object> maps = new ArrayList<Object>();
        for (RecipeMap<?> map : RecipeMap.getRecipeMaps()) {
            maps.add(map);
        }
        return maps;
    }

    static void convertMap(Object mapObj, List<QuestRecipeResolver.ProcRecipe> sink) {
        RecipeMap<?> map = (RecipeMap<?>) mapObj;
        String name;
        try {
            name = "gt:" + map.getUnlocalizedName();
        } catch (Throwable t) {
            name = "gt";
        }
        for (Recipe recipe : map.getRecipeList()) {
            try {
                QuestRecipeResolver.ProcRecipe r = convert(recipe, name, map);
                if (r != null) {
                    sink.add(r);
                }
            } catch (Throwable ignored) {
                // 壊れた/特殊なレシピは飛ばす
            }
        }
    }

    private static QuestRecipeResolver.ProcRecipe convert(Recipe recipe, String source, RecipeMap<?> map) {
        if (recipe.isHidden()) {
            return null;
        }
        List<ItemStack> outputs = new ArrayList<ItemStack>();
        for (ItemStack out : recipe.getOutputs()) {
            if (out != null && !out.isEmpty()) {
                outputs.add(out.copy());
            }
        }
        List<QuestRecipeResolver.FluidAmount> fluidOutputs = new ArrayList<QuestRecipeResolver.FluidAmount>();
        for (FluidStack fs : recipe.getFluidOutputs()) {
            if (fs != null && fs.getFluid() != null && fs.amount > 0) {
                fluidOutputs.add(new QuestRecipeResolver.FluidAmount(fs.getFluid().getName(), fs.amount));
            }
        }
        if (outputs.isEmpty() && fluidOutputs.isEmpty()) {
            return null;
        }

        List<QuestRecipeResolver.Input> inputs = new ArrayList<QuestRecipeResolver.Input>();
        for (GTRecipeInput in : recipe.getInputs()) {
            ItemStack[] stacks = in.getInputStacks();
            if (stacks == null || stacks.length == 0) {
                if (in.isNonConsumable()) {
                    continue;
                }
                return null;
            }
            inputs.add(new QuestRecipeResolver.Input(stacks, Math.max(1, in.getAmount()), !in.isNonConsumable()));
        }
        List<QuestRecipeResolver.FluidAmount> fluidInputs = new ArrayList<QuestRecipeResolver.FluidAmount>();
        for (GTRecipeInput in : recipe.getFluidInputs()) {
            FluidStack fs = in.getInputFluidStack();
            if (fs == null || fs.getFluid() == null) {
                return null;
            }
            if (in.isNonConsumable()) {
                continue;
            }
            fluidInputs.add(new QuestRecipeResolver.FluidAmount(fs.getFluid().getName(), Math.max(1, fs.amount)));
        }
        QuestRecipeResolver.ProcRecipe r = new QuestRecipeResolver.ProcRecipe(inputs, fluidInputs, outputs, fluidOutputs,
                Math.max(1, recipe.getDuration()), source);
        r.gtMap = map;
        r.eut = Math.max(0L, recipe.getEUt());
        r.tier = r.eut <= 0 ? 0 : gregtech.api.util.GTUtility.getTierByVoltage(r.eut);
        return r;
    }
}
