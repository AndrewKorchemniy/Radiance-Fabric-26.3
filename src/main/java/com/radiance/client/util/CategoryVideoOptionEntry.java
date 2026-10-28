package com.radiance.client.util;

import com.google.common.collect.ImmutableList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

public class CategoryVideoOptionEntry extends OptionsList.Entry {

    private final Component text;
    private final int textWidth;
    private final Minecraft client;
    private final OptionsList parent;

    public CategoryVideoOptionEntry(Component text, OptionsList parent) {
        super(ImmutableList.of(), null);

        this.client = Minecraft.getInstance();
        this.parent = parent;

        this.text = text;
        this.textWidth = this.client.font.width(this.text);
    }

    @Override
    public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY,
        boolean hovered, float tickDelta) {
        context.text(
            this.client.font, this.text, parent.getWidth() / 2 - this.textWidth / 2,
            this.getContentBottom() - 9 - 1, CommonColors.WHITE
        );
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return ImmutableList.of();
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
        return ImmutableList.of();
    }
}
