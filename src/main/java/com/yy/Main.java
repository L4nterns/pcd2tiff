package com.yy;

import static org.apache.commons.lang3.StringUtils.SPACE;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.imageio.ImageWriteParam;

import org.geotools.coverage.CoverageFactoryFinder;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class Main {

    private static final String PCD_URL = "http://localhost:999/files/output.pcd";

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(1, 1, TimeUnit.MINUTES))
            .build();

    private static final float WIDTH_SCALE = 1F; // 垛位横向缩放比例
    private static final float HEIGHT_SCALE = 0.89F; // 垛位纵向缩放比例
    private static final float LON_OFFSET = -0.0007F; // 整体垛位横向偏移量
    private static final float LAT_OFFSET = 0F; // 整体垛位纵向偏移量

    private static final float MIN_LON = 119.6906F;
    private static final float MIN_LAT = 39.92551F;

    private static final String OUTPUT_TIFF_PATH = System.getProperty("java.io.tmpdir") + "sample.tiff";

    public static void main(String[] args) {
        pcd2tiff();
    }

    public static void pcd2tiff() {
        String content = getPcdContent();

        List<Float[]> points = parsePcdPoints(content);

        prehandlePoints(points);

        createTiff(points);
    }

    public static String getPcdContent() {
        Request request = new Request.Builder()
                .url(PCD_URL)
                .get()
                .build();

        Call call = HTTP_CLIENT.newCall(request);

        byte[] bytes;
        try (Response response = call.execute()) {
            if (response.code() != 200) {
                throw new RuntimeException("pcd文件获取失败");
            }

            ResponseBody responseBody = response.body();
            bytes = responseBody.bytes();
        } catch (IOException e) {
            throw new RuntimeException("pcd文件读取失败，请检查网络连接");
        }

        return new String(bytes);
    }

    public static List<Float[]> parsePcdPoints(String content) {
        List<Float[]> points = new ArrayList<>();

        boolean headerParsed = false;
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!headerParsed) {
                    if (line.startsWith("DATA ")) {
                        headerParsed = true;
                    }
                    continue;
                }
                Float[] point = Stream.of(line.split(SPACE))
                        .map(part -> Float.valueOf(part))
                        .toArray(size -> new Float[3]);
                points.add(point);
            }
        } catch (IOException e) {
            throw new RuntimeException("pcd点集解析失败，请检查文件内容");
        }

        return points;
    }

    public static void prehandlePoints(List<Float[]> points) {
        points.forEach(v1 -> {
            Float x = v1[0];
            v1[0] = v1[1];
            v1[1] = x;
        });

        final float maxX = points.stream()
                .map(point -> point[0])
                .max(Float::compare)
                .orElse(0F);
        points.forEach(v1 -> {
            v1[0] = maxX - v1[0];
        });

        points.forEach(v1 -> {
            v1[0] = v1[0] * WIDTH_SCALE;
            v1[1] = v1[1] * HEIGHT_SCALE;
        });
    }

    public static void createTiff(List<Float[]> points) {
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;

        for (Float[] point : points) {
            minX = Math.min(minX, point[0]);
            maxX = Math.max(maxX, point[0]);
            minY = Math.min(minY, point[1]);
            maxY = Math.max(maxY, point[1]);
            minZ = Math.min(minZ, point[2]);
            maxZ = Math.max(maxZ, point[2]);
        }

        int width = (int) Math.floor(maxX - minX);
        int height = (int) Math.floor(maxX - minX);

        float[][] elevationData = new float[height][width];

        float pixelSizeX = (maxX - minX) / (width - 1);
        float pixelSizeY = (maxY - minY) / (height - 1);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                elevationData[y][x] = Float.NaN;
            }
        }

        for (Float[] point : points) {
            int x = Math.round((point[0] - minX) / pixelSizeX);
            int y = height - 1 - Math.round((point[1] - minY) / pixelSizeY);

            if (x < width && y >= 0 && y < height) {
                if (Float.isNaN(elevationData[y][x]) || elevationData[y][x] < point[2]) {
                    elevationData[y][x] = point[2];
                }
            }
        }

        fillMissingData(elevationData, width, height);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float value = elevationData[y][x];

                double normalizedValue = (value - minZ) / (maxZ - minZ);
                double power = 0.2;
                normalizedValue = Math.pow(normalizedValue, power);

                int grayValue = 255 - (int) Math.min(255, Math.round(normalizedValue * 255));
                int alphaValue = (int) Math.min(255, Math.round(normalizedValue * 225));

                int argb = (alphaValue << 24) | (grayValue << 16) | (grayValue << 8) | grayValue;

                image.setRGB(x, y, argb);
            }
        }

        image.createGraphics().drawImage(image, 0, 0, null);

        CoordinateReferenceSystem crs = null;
        try {
            crs = CRS.decode("EPSG:4490", true);
        } catch (Exception e) {
        }

        double minLon = MIN_LON + LON_OFFSET - minX * 0.00001;
        double maxLon = MIN_LON + LON_OFFSET - maxX * 0.00001;
        double minLat = MIN_LAT + LAT_OFFSET + minY * 0.00001;
        double maxLat = MIN_LAT + LAT_OFFSET + maxY * 0.00001;

        ReferencedEnvelope mapExtent = new ReferencedEnvelope(minLon, maxLon, minLat, maxLat, crs);

        GridCoverageFactory factory = CoverageFactoryFinder.getGridCoverageFactory(null);
        GridCoverage2D coverage = factory.create("sample", image, mapExtent);

        GeoTiffWriteParams writeParams = new GeoTiffWriteParams();
        writeParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        writeParams.setCompressionType("LZW");
        writeParams.setCompressionQuality(1F);
        writeParams.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
        writeParams.setTiling(256, 256);

        ParameterValueGroup params = new GeoTiffFormat().getWriteParameters();
        params.parameter(AbstractGridFormat.GEOTOOLS_WRITE_PARAMS.getName().toString())
                .setValue(writeParams);

        File outputFile = new File(OUTPUT_TIFF_PATH);

        GeoTiffWriter writer = null;
        try {
            writer = new GeoTiffWriter(outputFile);
            writer.write(coverage, params.values().toArray(new GeneralParameterValue[0]));
        } catch (Exception e) {
            throw new RuntimeException("tiff文件写入失败，请检查输出路径是否正确");
        } finally {
            writer.dispose();
        }
    }

    private static void fillMissingData(float[][] elevationData, int width, int height) {
        for (int y = 0; y < height; y++) {
            interpolateLinear(elevationData[y], width);
        }

        for (int x = 0; x < width; x++) {
            float[] columnData = new float[height];
            for (int y = 0; y < height; y++) {
                columnData[y] = elevationData[y][x];
            }

            interpolateLinear(columnData, height);

            for (int y = 0; y < height; y++) {
                elevationData[y][x] = columnData[y];
            }
        }
    }

    private static void interpolateLinear(float[] data, int length) {
        int lastValidIndex = -1;

        for (int currentIndex = 0; currentIndex < length; currentIndex++) {
            if (!Float.isNaN(data[currentIndex])) {
                if (lastValidIndex != -1) {
                    float startValue = data[lastValidIndex];
                    float endValue = data[currentIndex];
                    int gap = currentIndex - lastValidIndex;
                    float step = (endValue - startValue) / gap;

                    for (int i = lastValidIndex + 1; i < currentIndex; i++) {
                        data[i] = startValue + step * (i - lastValidIndex);
                    }
                }
                lastValidIndex = currentIndex;
            }
        }
    }
}
