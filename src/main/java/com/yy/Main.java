package com.yy;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(15, 10, TimeUnit.MINUTES))
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

    public static void main(String[] args) throws IOException {
        pcd2tiff();
    }

    public static void pcd2tiff() {
        String content = getPcdContent("http://localhost:999/files/output.pcd");
        Pcd pcd = parsePcdData(content);

        try {
            prehandlePointList(pcd.pointList);

            createGeoTiff(pcd.pointList);
        } catch (Exception e) {
            throw new RuntimeException("GeoTIFF文件创建失败");
        }
    }

    private static String getPcdContent(String url) {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        Call call = HTTP_CLIENT.newCall(request);

        try (Response response = call.execute()) {
            if (response.code() != 200) {
                throw new RuntimeException("pcd文件读取失败，请检查文件内容");
            }

            ResponseBody responseBody = response.body();
            return responseBody.string();
        } catch (IOException e) {
            throw new RuntimeException("pcd文件读取失败，请检查网络连接");
        }
    }

    private static class Pcd {
        private String[] fields;
        private int width;
        private int height;
        private int points;
        private List<Float[]> pointList;

        public String[] getFields() {
            return fields;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public int getPoints() {
            return points;
        }

        public List<Float[]> getPointList() {
            return pointList;
        }
    }

    /**
     * 解析pcd内容
     * 
     * @param content
     * @param pointList
     * @return
     */
    private static Pcd parsePcdData(String content) {
        Pcd pcd = new Pcd();
        pcd.pointList = new ArrayList<>();
        boolean headerParsed = false;

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!headerParsed) {
                    if (line.startsWith(PcdPrefixEnum.COMMENT.getValue()) ||
                            line.startsWith(PcdPrefixEnum.VERSION.getValue()) ||
                            line.startsWith(PcdPrefixEnum.COUNT.getValue()) ||
                            line.startsWith(PcdPrefixEnum.TYPE.getValue()) ||
                            line.startsWith(PcdPrefixEnum.SIZE.getValue()) ||
                            line.startsWith(PcdPrefixEnum.VIEWPOINT.getValue())) {
                        continue;
                    } else if (line.startsWith(PcdPrefixEnum.FIELDS.getValue())) {
                        pcd.fields = line.substring(PcdPrefixEnum.FIELDS.getValue().length())
                                .split(StringUtils.SPACE);
                    } else if (line.startsWith(PcdPrefixEnum.WIDTH.getValue())) {
                        pcd.width = Integer.parseInt(line.substring(PcdPrefixEnum.WIDTH.getValue().length()));
                    } else if (line.startsWith(PcdPrefixEnum.HEIGHT.getValue())) {
                        pcd.height = Integer.parseInt(line.substring(PcdPrefixEnum.HEIGHT.getValue().length()));
                    } else if (line.startsWith(PcdPrefixEnum.POINTS.getValue())) {
                        pcd.points = Integer.parseInt(line.substring(PcdPrefixEnum.POINTS.getValue().length()));
                    } else if (line.startsWith(PcdPrefixEnum.DATA.getValue())) {
                        if (!line.equals(PcdPrefixEnum.DATA.getValue() + "ascii")) {
                            throw new RuntimeException("当前仅支持ascii格式");
                        }
                        headerParsed = true;
                    }
                } else {
                    Float[] point = parsePoint(line);
                    if (point != null && point.length >= 3) {
                        pcd.pointList.add(point);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("pcd数据解析失败");
        }

        if (pcd.width <= 0 || pcd.height <= 0 || pcd.pointList.size() != pcd.width * pcd.height) {
            throw new RuntimeException("pcd数据解析失败，请检查文件格式");
        }

        return pcd;
    }

    private static Float[] parsePoint(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        try {
            String[] parts = line.trim().split("\\s+");
            Float[] point = new Float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                point[i] = Float.valueOf(parts[i]);
            }
            return point;
        } catch (NumberFormatException e) {
            throw new RuntimeException("解析点坐标失败");
        }
    }

    /**
     * 预处理点云数据
     * 
     * @param pointList
     */
    public static void prehandlePointList(List<Float[]> pointList) {
        // 交换x和y
        pointList.forEach(v1 -> {
            Float x = v1[0];
            v1[0] = v1[1];
            v1[1] = x;
        });

        // 翻转x
        final float maxX = pointList.stream()
                .map(point -> point[0])
                .max(Float::compare)
                .orElse(0f);
        pointList.forEach(v1 -> {
            v1[0] = maxX - v1[0];
        });
    }

    /**
     * 创建geotiff
     * 
     * @param pointList
     */
    public static void createGeoTiff(List<Float[]> pointList) {
        try {
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;

            for (Float[] point : pointList) {
                minX = Math.min(minX, point[0]);
                maxX = Math.max(maxX, point[0]);
                minY = Math.min(minY, point[1]);
                maxY = Math.max(maxY, point[1]);
                minZ = Math.min(minZ, point[2]);
                maxZ = Math.max(maxZ, point[2]);
            }

            int width = (int) (maxX - minX);
            int height = (int) (maxY - minY);

            // 创建高程数据网格
            float[][] elevationData = new float[height][width];

            // 初始化为NaN
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    elevationData[y][x] = Float.NaN;
                }
            }

            // 映射点云数据到网格
            for (Float[] point : pointList) {
                // 恢复原始映射方式
                int x = Math.round(point[0] - minX);
                int y = height - 1 - Math.round(point[1] - minY);

                if (x >= 0 && x < width && y >= 0 && y < height) {
                    if (Float.isNaN(elevationData[y][x]) || elevationData[y][x] < point[2]) {
                        elevationData[y][x] = point[2];
                    }
                }
            }

            fillMissingData(elevationData, width, height);

            // 创建带透明通道的图像
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            // 将高程数据映射到颜色值
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float value = elevationData[y][x];

                    if (Float.isNaN(value)) {
                        // 空白区域设为透明
                        image.setRGB(x, y, 0x00000000); // 完全透明 (alpha=0)
                    } else {
                        // 高程越大越黑，同时高程越低越透明

                        // 灰度值：高程越高越黑
                        int grayValue = 255 - Math.min(255, Math.max(0,
                                Math.round(((value - minZ) / (maxZ - minZ)) * 255)));

                        // 确保可见度
                        if (grayValue > 205) {
                            grayValue = 205; // 最亮不超过205
                        }

                        // 透明度：高程越低越透明
                        // 将高程从0-100%映射到30-255的alpha值（最低处至少30%不透明度）
                        int alphaValue = 30 + Math.min(225, Math.max(0,
                                Math.round(((value - minZ) / (maxZ - minZ)) * 225)));

                        // 创建带有高程相关透明度的颜色 (ARGB格式)
                        int argb = (alphaValue << 24) | (grayValue << 16) | (grayValue << 8) | grayValue;
                        image.setRGB(x, y, argb);
                    }
                }
            }

            // 处理图像以保留透明度
            try {
                File pngFile = new File("temp.png");
                ImageIO.write(image, "PNG", pngFile);
                image = ImageIO.read(pngFile);
                pngFile.delete();
            } catch (IOException e) {
                System.err.println("无法创建临时PNG文件");
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

            // 创建GridCoverage
            GridCoverageFactory factory = CoverageFactoryFinder.getGridCoverageFactory(null);
            GridCoverage2D coverage = factory.create("sample", finalImage, mapExtent);

            // 设置GeoTIFF写入参数
            GeoTiffWriteParams writeParams = new GeoTiffWriteParams();
            writeParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParams.setCompressionType("LZW");
            writeParams.setCompressionQuality(1F);
            writeParams.setTilingMode(GeoTiffWriteParams.MODE_EXPLICIT);
            writeParams.setTiling(256, 256);

            // 创建GeoTIFF格式参数
            ParameterValueGroup params = new GeoTiffFormat().getWriteParameters();
            params.parameter(AbstractGridFormat.GEOTOOLS_WRITE_PARAMS.getName().toString())
                    .setValue(writeParams);

            // 输出文件
            File outputFile = new File("sample.tif");

            // 写入GeoTIFF文件
            GeoTiffWriter writer = new GeoTiffWriter(outputFile);
            try {
                writer.write(coverage, params.values().toArray(new GeneralParameterValue[0]));
            } finally {
                writer.dispose();
            }

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("创建GeoTIFF文件失败");
        }
    }

    /**
     * 填充缺失的数据点以消除横线
     */
    private static void fillMissingData(float[][] data, int width, int height) {
        // 先进行横向填充
        for (int y = 0; y < height; y++) {
            fillRowGaps(data[y], width);
        }

        // 再进行纵向填充
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

    /**
     * 填充一维数组中的缺失值
     */
    private static void fillRowGaps(float[] row, int length) {
        int start = -1;

        // 查找并填充空隙
        for (int i = 0; i < length; i++) {
            if (!Float.isNaN(row[i])) {
                if (start != -1) {
                    // 找到了一个空隙的结束位置，进行插值填充
                    float startValue = row[start];
                    float endValue = row[i];
                    float increment = (endValue - startValue) / (i - start);

                    // 线性插值填充
                    for (int j = start + 1; j < i; j++) {
                        row[j] = startValue + increment * (j - start);
                    }
                }
                start = i;
            }
        }
    }
}