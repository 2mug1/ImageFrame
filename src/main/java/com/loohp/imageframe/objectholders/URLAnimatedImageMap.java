/*
 * This file is part of ImageFrame.
 *
 * Copyright (C) 2022. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2022. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.imageframe.objectholders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.loohp.imageframe.utils.GifReader;
import com.loohp.imageframe.utils.HTTPRequestUtils;
import com.loohp.imageframe.utils.MapUtils;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class URLAnimatedImageMap extends URLImageMap {

    private static final byte[] EMPTY_COLORS = new byte[MapUtils.COLOR_ARRAY_LENGTH];

    public static URLAnimatedImageMap create(ImageMapManager manager, String url, int width, int height, UUID creator) throws Exception {
        World world = Bukkit.getWorlds().get(0);
        int mapsCount = width * height;
        List<MapView> mapViews = new ArrayList<>(mapsCount);
        IntList mapIds = new IntArrayList(mapsCount);
        for (int i = 0; i < mapsCount; i++) {
            MapView mapView = Bukkit.createMap(world);
            for (MapRenderer renderer : mapView.getRenderers()) {
                mapView.removeRenderer(renderer);
            }
            mapViews.add(mapView);
            mapIds.add(mapView.getId());
        }
        URLAnimatedImageMap map = new URLAnimatedImageMap(manager, -1, url, new BufferedImage[mapsCount][], mapViews, mapIds, width, height, creator, System.currentTimeMillis());
        for (int i = 0; i < mapViews.size(); i++) {
            MapView mapView = mapViews.get(i);
            mapView.addRenderer(new URLAnimatedImageMapRenderer(map, i));
        }
        map.update();
        return map;
    }

    public static URLAnimatedImageMap load(ImageMapManager manager, File folder, JsonObject json) throws Exception {
        if (!json.get("type").getAsString().equals(URLAnimatedImageMap.class.getName())) {
            throw new IllegalArgumentException("invalid type");
        }
        int imageIndex = json.get("index").getAsInt();
        String url = json.get("url").getAsString();
        int width = json.get("width").getAsInt();
        int height = json.get("height").getAsInt();
        long creationTime = json.get("creationTime").getAsLong();
        UUID creator = UUID.fromString(json.get("creator").getAsString());
        JsonArray mapDataJson = json.get("mapdata").getAsJsonArray();
        List<MapView> mapViews = new ArrayList<>(mapDataJson.size());
        IntList mapIds = new IntArrayList(mapDataJson.size());
        BufferedImage[][] cachedImages = new BufferedImage[mapDataJson.size()][];
        int i = 0;
        for (JsonElement dataJson : mapDataJson) {
            JsonObject jsonObject = dataJson.getAsJsonObject();
            int mapId = jsonObject.get("mapid").getAsInt();
            mapIds.add(mapId);
            mapViews.add(Bukkit.getMap(mapId));
            JsonArray framesArray = jsonObject.get("images").getAsJsonArray();
            BufferedImage[] images = new BufferedImage[framesArray.size()];
            int u = 0;
            for (JsonElement element : framesArray) {
                images[u++] = ImageIO.read(new File(folder, element.getAsString()));
            }
            cachedImages[i++] = images;
        }
        URLAnimatedImageMap map = new URLAnimatedImageMap(manager, imageIndex, url, cachedImages, mapViews, mapIds, width, height, creator, creationTime);
        for (int u = 0; u < mapViews.size(); u++) {
            MapView mapView = mapViews.get(u);
            for (MapRenderer renderer : mapView.getRenderers()) {
                mapView.removeRenderer(renderer);
            }
            mapView.addRenderer(new URLAnimatedImageMapRenderer(map, u));
        }
        return map;
    }

    private final BufferedImage[][] cachedImages;

    private byte[][][] cachedColors;

    private URLAnimatedImageMap(ImageMapManager manager, int imageIndex, String url, BufferedImage[][] cachedImages, List<MapView> mapViews, IntList mapIds, int width, int height, UUID creator, long creationTime) {
        super(manager, imageIndex, url, mapViews, mapIds, width, height, creator, creationTime);
        this.cachedImages = cachedImages;
        cacheColors();
    }

    public void cacheColors() {
        if (cachedImages == null) {
            return;
        }
        if (cachedImages[0] == null) {
            return;
        }
        cachedColors = new byte[cachedImages.length][][];
        int i = 0;
        for (BufferedImage[] images : cachedImages) {
            byte[][] data = new byte[images.length][];
            cachedColors[i++] = data;
            int u = 0;
            for (BufferedImage image : images) {
                data[u++] = MapPalette.imageToBytes(image);
            }
        }
    }

    @Override
    public void update() throws Exception {
        List<GifReader.ImageFrame> frames = GifReader.readGif(new ByteArrayInputStream(HTTPRequestUtils.download(url))).get();
        List<BufferedImage> images = new ArrayList<>();
        for (int currentTime = 0; ; currentTime += 50) {
            int index = GifReader.getFrameAt(frames, currentTime);
            if (index < 0) {
                break;
            }
            images.add(frames.get(index).getImage());
        }
        for (int i = 0; i < cachedImages.length; i++) {
            cachedImages[i] = new BufferedImage[images.size()];
        }
        int index = 0;
        for (BufferedImage image : images) {
            image = MapUtils.resize(image, width, height);
            int i = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    cachedImages[i++][index] = MapUtils.getSubImage(image, x, y);
                }
            }
            index++;
        }
        cacheColors();
    }

    @Override
    public boolean requiresAnimationService() {
        return true;
    }

    @Override
    public byte[] getRawAnimationColors(int currentTick, int index) {
        if (cachedColors == null) {
            return EMPTY_COLORS;
        }
        byte[][] colors = cachedColors[index];
        return colors[currentTick % colors.length];
    }

    @Override
    public void save(File dataFolder) throws Exception {
        File folder = new File(dataFolder, String.valueOf(imageIndex));
        folder.mkdirs();
        JsonObject json = new JsonObject();
        json.addProperty("type", this.getClass().getName());
        json.addProperty("index", imageIndex);
        json.addProperty("url", url);
        json.addProperty("width", width);
        json.addProperty("height", height);
        json.addProperty("creator", creator.toString());
        json.addProperty("creationTime", creationTime);
        JsonArray mapDataJson = new JsonArray();
        int u = 0;
        for (int i = 0; i < mapViews.size(); i++) {
            JsonObject dataJson = new JsonObject();
            dataJson.addProperty("mapid", mapIds.getInt(i));
            JsonArray framesArray = new JsonArray();
            for (BufferedImage image : cachedImages[i]) {
                framesArray.add(u++ + ".png");
            }
            dataJson.add("images", framesArray);
            mapDataJson.add(dataJson);
        }
        json.add("mapdata", mapDataJson);
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(Files.newOutputStream(new File(folder, "data.json").toPath()), StandardCharsets.UTF_8))) {
            pw.println(GSON.toJson(json));
            pw.flush();
        }
        int i = 0;
        for (BufferedImage[] images : cachedImages) {
            for (BufferedImage image : images) {
                ImageIO.write(image, "png", new File(folder, i++ + ".png"));
            }
        }
    }

    public static class URLAnimatedImageMapRenderer extends MapRenderer {

        private final URLAnimatedImageMap parent;
        private final int index;

        public URLAnimatedImageMapRenderer(URLAnimatedImageMap parent, int index) {
            this.parent = parent;
            this.index = index;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            byte[] colors = parent.getRawAnimationColors(parent.getManager().getCurrentAnimationTick(), index);
            for (int i = 0; i < colors.length; i++) {
                canvas.setPixel(i % MapUtils.MAP_WIDTH, i / MapUtils.MAP_WIDTH, colors[i]);
            }
        }
    }

}