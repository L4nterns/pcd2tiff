package com.yy;

import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.SPACE;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

import org.apache.commons.lang3.StringUtils;
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

import lombok.Getter;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class Main {

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(1, 1, TimeUnit.MINUTES))
            .build();

    @Getter
    public enum PcdPrefixEnum {
        COMMENT("#"),
        VERSION("VERSION "),
        FIELDS("FIELDS "),
        SIZE("SIZE "),
        TYPE("TYPE "),
        COUNT("COUNT "),
        WIDTH("WIDTH "),
        HEIGHT("HEIGHT "),
        VIEWPOINT("VIEWPOINT "),
        POINTS("POINTS "),
        DATA("DATA ");

        private final String value;

        PcdPrefixEnum(String value) {
            this.value = value;
        }
    }

    public static void main(String[] args) {
        pcd2tiff();
    }

    public static void pcd2tiff() {
        Request request = new Request.Builder()
                .url("http://localhost:999/files/output.pcd")
                .get()
                .build();

        Call call = HTTP_CLIENT.newCall(request);

        String content;
        try (Response response = call.execute()) {
            if (response.code() != 200) {
                throw new RuntimeException("pcd文件读取失败，请检查文件内容");
            }

            ResponseBody responseBody = response.body();
            content = responseBody.string();
        } catch (IOException e) {
            throw new RuntimeException("pcd文件读取失败，请检查网络连接");
        }

        List<Float[]> points = new ArrayList<>();
        boolean headerParsed = false;
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!headerParsed) {
                    if (line.startsWith(PcdPrefixEnum.DATA.getValue())) {
                        headerParsed = true;
                    }
                } else {
                    Float[] point = Stream.of(line.split(SPACE))
                            .map(v1 -> Float.valueOf(v1))
                            .toArray(size -> new Float[size]);
                    points.add(point);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("pcd数据解析失败");
        }

        try {
            prehandlePoints(points);

            createGeoTiff(points);
        } catch (Exception e) {
            throw new RuntimeException("GeoTIFF文件创建失败");
        }
    }

    /**
     * 预处理点云数据
     * 
     * @param points
     */
    public static void prehandlePoints(List<Float[]> points) {
        points.forEach(v1 -> {
            Float x = v1[0];
            v1[0] = v1[1];
            v1[1] = x;
        });

        final float maxX = points.stream()
                .map(point -> point[0])
                .max(Float::compare)
                .orElse(0f);
        points.forEach(v1 -> {
            v1[0] = maxX - v1[0];
        });
    }

    /**
     * 创建geotiff
     * 
     * @param points
     */
    public static void createGeoTiff(List<Float[]> points) {
        try {
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

            int width = (int) (maxX - minX);
            int height = (int) (maxY - minY);

            float[][] elevationData = new float[height][width];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    elevationData[y][x] = Float.NaN;
                }
            }

            for (Float[] point : points) {
                int x = Math.round(point[0] - minX);
                int y = height - 1 - Math.round(point[1] - minY);

                if (x >= 0 && x < width && y >= 0 && y < height) {
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

                    if (Float.isNaN(value)) {
                    } else {

                        int grayValue = 255 - Math.min(255, Math.max(0,
                                Math.round(((value - minZ) / (maxZ - minZ)) * 255)));

                        if (grayValue > 205) {
                        }

                        int alphaValue = 30 + Math.min(225, Math.max(0,
                                Math.round(((value - minZ) / (maxZ - minZ)) * 225)));

                        int argb = (alphaValue << 24) | (grayValue << 16) | (grayValue << 8) | grayValue;
                        image.setRGB(x, y, argb);
                    }
                }
            }

            try {
                File pngFile = new File("temp.png");
                ImageIO.write(image, "PNG", pngFile);
                image = ImageIO.read(pngFile);
                pngFile.delete();
            } catch (IOException e) {
                throw new RuntimeException("无法创建临时PNG文件");
            }

            BufferedImage finalImage = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
            finalImage.createGraphics().drawImage(image, 0, 0, null);

            CoordinateReferenceSystem crs = CRS.decode("EPSG:4490", true);

            double minLon = 119.6906 - minX * 0.00001;
            double maxLon = 119.6906 - maxX * 0.00001;
            double minLat = 39.92551 + minY * 0.00001;
            double maxLat = 39.92551 + maxY * 0.00001;

            ReferencedEnvelope mapExtent = new ReferencedEnvelope(
                    minLon, maxLon, minLat, maxLat, crs);

            GridCoverageFactory factory = CoverageFactoryFinder.getGridCoverageFactory(null);
            GridCoverage2D coverage = factory.create("sample", finalImage, mapExtent);

            GeoTiffWriteParams writeParams = new GeoTiffWriteParams();
            writeParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParams.setCompressionType("LZW");
            writeParams.setCompressionQuality(1F);
            writeParams.setTilingMode(GeoTiffWriteParams.MODE_EXPLICIT);
            writeParams.setTiling(256, 256);

            ParameterValueGroup params = new GeoTiffFormat().getWriteParameters();
            params.parameter(AbstractGridFormat.GEOTOOLS_WRITE_PARAMS.getName().toString())
                    .setValue(writeParams);

            File outputFile = new File("sample.tiff");

            GeoTiffWriter writer = new GeoTiffWriter(outputFile);
            try {
                writer.write(coverage, params.values().toArray(new GeneralParameterValue[0]));
            } finally {
                writer.dispose();
            }

        } catch (Exception e) {
            throw new RuntimeException("创建GeoTIFF文件失败");
        }
    }

    private static void fillMissingData(float[][] data, int width, int height) {
        for (int y = 0; y < height; y++) {
            fillRowGaps(data[y], width);
        }

        for (int x = 0; x < width; x++) {
            float[] column = new float[height];
            for (int y = 0; y < height; y++) {
                column[y] = data[y][x];
            }

            fillRowGaps(column, height);

            for (int y = 0; y < height; y++) {
                data[y][x] = column[y];
            }
        }
    }

    private static void fillRowGaps(float[] row, int length) {
        int start = -1;

        for (int i = 0; i < length; i++) {
            if (!Float.isNaN(row[i])) {
                if (start != -1) {
                    float startValue = row[start];
                    float endValue = row[i];
                    float increment = (endValue - startValue) / (i - start);

                    for (int j = start + 1; j < i; j++) {
                        row[j] = startValue + increment * (j - start);
                    }
                }
                start = i;
            }
        }
    }
}