package com.example.camerapathaddon.client.screen;

import com.example.camerapathaddon.screen.CameraControlScreenHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/** The camera control block's slots: route book and drone control stick. Drawn without a texture, like tudursvehiclemod's Drone Center slot screen. */
public class CameraControlSlotsScreen extends HandledScreen<CameraControlScreenHandler> {

	private static final int PANEL = 0xFFC6C6C6;
	private static final int SLOT_FILL = 0xFF8B8B8B;
	private static final int SLOT_DARK = 0xFF373737;
	private static final int SLOT_LIGHT = 0xFFFFFFFF;
	private static final int LABEL = 0xFF404040;

	public CameraControlSlotsScreen(CameraControlScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		this.backgroundWidth = 176;
		this.backgroundHeight = 166;
		this.playerInventoryTitleY = this.backgroundHeight - 94;
	}

	@Override
	protected void init() {
		super.init();
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.back"), b -> {
			this.client.player.closeHandledScreen();
			this.client.setScreen(new CameraControlScreen(this.handler.getBlockPos()));
		}).dimensions(this.x + 104, this.y + 34, 62, 20).build());
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		context.fill(this.x, this.y, this.x + this.backgroundWidth, this.y + this.backgroundHeight, PANEL);
		drawSlot(context, this.x + CameraControlScreenHandler.BOOK_X, this.y + CameraControlScreenHandler.BOOK_Y);
		drawSlot(context, this.x + CameraControlScreenHandler.STICK_X, this.y + CameraControlScreenHandler.STICK_Y);
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				drawSlot(context, this.x + 8 + col * 18, this.y + 84 + row * 18);
			}
		}
		for (int col = 0; col < 9; col++) {
			drawSlot(context, this.x + 8 + col * 18, this.y + 142);
		}
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		super.drawForeground(context, mouseX, mouseY);
		context.drawText(this.textRenderer, Text.translatable("gui.camerapathaddon.slot_book"), 8, CameraControlScreenHandler.BOOK_Y + 4, LABEL, false);
		context.drawText(this.textRenderer, Text.translatable("gui.camerapathaddon.slot_stick"), 8, CameraControlScreenHandler.STICK_Y + 4, LABEL, false);
	}

	private static void drawSlot(DrawContext context, int x, int y) {
		context.fill(x - 1, y - 1, x + 17, y + 17, SLOT_DARK);
		context.fill(x, y, x + 18, y + 18, SLOT_LIGHT);
		context.fill(x, y, x + 16, y + 16, SLOT_FILL);
	}
}
