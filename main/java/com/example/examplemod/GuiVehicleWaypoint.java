package com.example.examplemod;

import java.io.IOException;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * {@link ItemVehicleWaypoint} から開かれる、車両の目的地(X/Y/Z)を入力するだけの簡易GUI。
 * 「決定」または Enter キーで {@link PacketSetVehicleWaypoint} をサーバーへ送信する。
 */
@SideOnly(Side.CLIENT)
public class GuiVehicleWaypoint extends GuiScreen {

    private static final double ARRIVAL_RADIUS = 4.0D;

    private final BlockPos initialPos;

    private GuiTextField fieldX;
    private GuiTextField fieldY;
    private GuiTextField fieldZ;

    public GuiVehicleWaypoint(BlockPos initialPos) {
        this.initialPos = initialPos;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cx = this.width / 2;
        int cy = this.height / 2;

        this.fieldX = new GuiTextField(0, this.fontRenderer, cx - 100, cy - 34, 60, 20);
        this.fieldY = new GuiTextField(1, this.fontRenderer, cx - 30, cy - 34, 60, 20);
        this.fieldZ = new GuiTextField(2, this.fontRenderer, cx + 40, cy - 34, 60, 20);

        this.fieldX.setText(Integer.toString(initialPos.getX()));
        this.fieldY.setText(Integer.toString(initialPos.getY()));
        this.fieldZ.setText(Integer.toString(initialPos.getZ()));
        this.fieldX.setFocused(true);

        this.buttonList.add(new GuiButton(0, cx - 60, cy + 10, 120, 20, I18n.format("examplemod.gui.vehicle_waypoint.set")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            confirmAndClose();
        }
    }

    private void confirmAndClose() {
        try {
            int x = Integer.parseInt(fieldX.getText().trim());
            int y = Integer.parseInt(fieldY.getText().trim());
            int z = Integer.parseInt(fieldZ.getText().trim());
            ModNetwork.CHANNEL.sendToServer(new PacketSetVehicleWaypoint(new BlockPos(x, y, z), ARRIVAL_RADIUS));
        } catch (NumberFormatException ignored) {
            // 数値が不正な場合は送信せずGUIを閉じるだけにする。
        }
        this.mc.displayGuiScreen(null);
    }

    @Override
    public void updateScreen() {
        this.fieldX.updateCursorCounter();
        this.fieldY.updateCursorCounter();
        this.fieldZ.updateCursorCounter();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) { // ESC
            this.mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == 28 || keyCode == 156) { // Enter / Numpad Enter
            confirmAndClose();
            return;
        }
        if (this.fieldX.isFocused()) {
            this.fieldX.textboxKeyTyped(typedChar, keyCode);
        } else if (this.fieldY.isFocused()) {
            this.fieldY.textboxKeyTyped(typedChar, keyCode);
        } else if (this.fieldZ.isFocused()) {
            this.fieldZ.textboxKeyTyped(typedChar, keyCode);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.fieldX.mouseClicked(mouseX, mouseY, mouseButton);
        this.fieldY.mouseClicked(mouseX, mouseY, mouseButton);
        this.fieldZ.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        int cx = this.width / 2;
        int cy = this.height / 2;

        this.drawCenteredString(this.fontRenderer, I18n.format("examplemod.gui.vehicle_waypoint.title"), cx, cy - 60, 0xFFFFFF);
        this.drawString(this.fontRenderer, "X", cx - 100, cy - 46, 0xA0A0A0);
        this.drawString(this.fontRenderer, "Y", cx - 30, cy - 46, 0xA0A0A0);
        this.drawString(this.fontRenderer, "Z", cx + 40, cy - 46, 0xA0A0A0);

        this.fieldX.drawTextBox();
        this.fieldY.drawTextBox();
        this.fieldZ.drawTextBox();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
