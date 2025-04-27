package com.yy;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.geotools.coverage.CoverageFactoryFinder;
import org.geotools.coverage.GridSampleDimension;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GeneralGridEnvelope;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.DirectPosition2D;
import org.geotools.referencing.CRS;
import org.opengis.coverage.ColorInterpretation;
import org.opengis.coverage.grid.Format;
import org.opengis.coverage.grid.GridCoverageReader;
import org.opengis.coverage.grid.GridCoverageWriter;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.*;

import lombok.Getter;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.geosolutions.jaiext.nullop.NullDescriptor;

import static com.yy.Main.PcdPrefixEnum.*;

// GeoTools imports - Using the api package as per your provided code
import org.geotools.coverage.grid.GridCoverage2D;
// GeoTools imports
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.gce.geotiff.GeoTiffWriter;
// Add this import for Envelope2D
import org.geotools.geometry.Envelope2D; // Ensure this import is present and resolves
import org.geotools.geometry.GeneralEnvelope;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.parameter.DefaultParameterDescriptorGroup;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import javax.imageio.ImageWriteParam;
import javax.imageio.spi.ImageWriterSpi; // Might be needed depending on setup
import java.awt.image.RenderedImage; // Import RenderedImage

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferFloat;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.awt.Transparency;
import java.awt.image.DataBufferInt;
import java.lang.reflect.Field;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class Main {

    // pcd格式像这样
    //
    // # .PCD v0.7 - Point Cloud Data file format
    // VERSION 0.7
    // FIELDS x y z
    // SIZE 4 4 4
    // TYPE F F F
    // COUNT 1 1 1
    // WIDTH 900000
    // HEIGHT 1
    // VIEWPOINT 0 0 0 1 0 0 0
    // POINTS 900000
    // DATA ascii
    // 0 0 0
    // 1 0 0
    // 2 0 0
    // 3 0 0
    // 4 0 0
    // 5 0 0
    // ...

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(
                    15,
                    10,
                    TimeUnit.MINUTES))
            .build();

    private ObjectMapper objectMapper;

    public static void main(String[] args) throws IOException {
        pcd2tiff();
    }

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

    public static void pcd2tiff() {
        Request request = new Request.Builder()
                .url("http://localhost:999/files/output.pcd") // Replace with your PCD file source
                .get()
                .build();

        Call call = HTTP_CLIENT.newCall(request);

        String content;
        try (Response response = call.execute()) {
            if (response.code() != 200) {
                throw new RuntimeException("pcd文件读取失败: HTTP " + response.code());
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new RuntimeException("pcd文件读取失败: Response body is null");
            }

            content = responseBody.string();
        } catch (IOException e) {
            throw new RuntimeException("pcd文件读取失败，请检查网络连接: " + e.getMessage(), e);
        }

        String[] fields = null; // Initialize to null
        String[] sizes = null; // Initialize to null
        String[] types = null; // Initialize to null
        int width = 0;
        int height = 0;
        int points = 0; // Initialize to 0
        List<Float[]> pointList = new ArrayList<>();

        boolean headerParsed = false; // Flag to indicate when header is done

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!headerParsed) {
                    if (line.startsWith(COMMENT.getValue()) ||
                            line.startsWith(VERSION.getValue()) ||
                            line.startsWith(COUNT.getValue()) ||
                            line.startsWith(VIEWPOINT.getValue())) {
                        continue;
                    } else if (line.startsWith(FIELDS.getValue())) {
                        fields = line.substring(FIELDS.getValue().length()).split(SPACE);
                    } else if (line.startsWith(SIZE.getValue())) {
                        sizes = line.substring(SIZE.getValue().length()).split(SPACE); // Corrected prefix
                    } else if (line.startsWith(TYPE.getValue())) {
                        types = line.substring(TYPE.getValue().length()).split(SPACE); // Corrected prefix
                    } else if (line.startsWith(WIDTH.getValue())) {
                        width = Integer.parseInt(line.substring(WIDTH.getValue().length()));
                    } else if (line.startsWith(HEIGHT.getValue())) {
                        height = Integer.parseInt(line.substring(HEIGHT.getValue().length()));
                    } else if (line.startsWith(POINTS.getValue())) {
                        points = Integer.parseInt(line.substring(POINTS.getValue().length()));
                    } else if (line.startsWith(DATA.getValue())) {
                        if (!line.equals(DATA.getValue() + "ascii")) {
                            throw new RuntimeException("当前仅支持ascii格式");
                        }
                        headerParsed = true; // Header is done, next lines are points
                    } else {
                        // Handle potential blank lines or unexpected lines before data
                        if (!line.trim().isEmpty()) {
                            System.err.println("Skipping unexpected header line: " + line);
                        }
                    }
                } else {
                    // Process point data lines
                    Float[] point = parsePoint(line);
                    if (point != null && point.length >= 3) { // Ensure point has at least X, Y, Z
                        pointList.add(point);
                    } else {
                        System.err.println("Skipping invalid point line: " + line);
                    }
                }
            }
        } catch (IOException e) {
            // Should not happen with StringReader, but good practice to catch
            System.err.println("Error reading point data: " + e.getMessage());
        }

        if (width <= 0 || height <= 0 || pointList.size() != width * height) {
            System.err.println("Parsed points count (" + pointList.size() + ") does not match width (" + width
                    + ") * height (" + height + ")");
            throw new RuntimeException("PCD 文件头部信息不完整或点数据不匹配");
        }

        try {
            createTiff(pointList, width, height);
            // pointList.stream().forEach(v1 -> {
            // System.out.println(v1[0] + "," + v1[1] + "," + v1[2]);
            // });
            System.out.println("GeoTIFF file 'output.tif' created successfully.");
        } catch (Exception e) {
            System.err.println("Error creating GeoTIFF: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("GeoTIFF文件创建失败", e);
        }
    }

    public static Float[] parsePoint(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null; // Skip empty lines
        }
        try {
            // Split by one or more spaces
            String[] parts = line.trim().split("\\s+");
            Float[] point = new Float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                point[i] = Float.valueOf(parts[i]);
            }
            return point;
        } catch (NumberFormatException e) {
            System.err.println("Error parsing point coordinates from line: " + line + " - " + e.getMessage());
            return null; // Return null for invalid lines
        }
    }

    public static void createTiff(List<Float[]> pointList, int width, int height) {
        handlePointList(pointList);

        // 调用简化版本的方法
        createSimpleTiff(pointList, width, height);
    }

    public static void handlePointList(List<Float[]> pointList) {
        pointList.forEach(v1 -> {
            Float x = v1[0];
            v1[0] = v1[1];
            v1[1] = x;
        });
    }

    /**
     * 创建单个GeoTIFF文件，内嵌所有地理参考信息
     * 适用于EPSG:4549坐标系，单位为米的点云数据
     */
    public static void createSimpleTiff(List<Float[]> pointList, int width, int height) {
        try {
            // 查找点云数据的实际范围
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

            System.out.println("点云数据范围(米): X[" + minX + ", " + maxX + "] Y[" + minY + ", " + maxY + "] Z[" + minZ + ", "
                    + maxZ + "]");

            // 确定合适的图像尺寸
            int newWidth = 750; // 图像宽度
            int newHeight = 1200; // 图像高度

            System.out.println("图像尺寸: " + newWidth + "x" + newHeight);

            // 创建高程数据数组
            float[][] elevationData = new float[newHeight][newWidth];

            // 计算单元格大小(米/像素)
            float pixelSizeX = (maxX - minX) / (newWidth - 1);
            float pixelSizeY = (maxY - minY) / (newHeight - 1);

            // 初始化为NaN
            for (int y = 0; y < newHeight; y++) {
                for (int x = 0; x < newWidth; x++) {
                    elevationData[y][x] = Float.NaN;
                }
            }

            // 映射点云数据到网格
            for (Float[] point : pointList) {
                // 恢复原始映射方式
                int x = Math.round((point[0] - minX) / pixelSizeX);
                int y = newHeight - 1 - Math.round((point[1] - minY) / pixelSizeY);

                if (x >= 0 && x < newWidth && y >= 0 && y < newHeight) {
                    if (Float.isNaN(elevationData[y][x]) || elevationData[y][x] < point[2]) {
                        elevationData[y][x] = point[2];
                    }
                }
            }
            
            // 填充缺失数据（处理横线问题）
            fillMissingData(elevationData, newWidth, newHeight);

            // 创建灰度图像
            BufferedImage image = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_BYTE_GRAY);

            // 将高程数据映射到灰度值
            for (int y = 0; y < newHeight; y++) {
                for (int x = 0; x < newWidth; x++) {
                    float value = elevationData[y][x];
                    int pixelValue;

                    if (Float.isNaN(value)) {
                        pixelValue = 255; // 无数据区域使用白色
                    } else {
                        // 映射高程值到灰度
                        pixelValue = Math.min(255, Math.max(0,
                                Math.round(((value - minZ) / (maxZ - minZ)) * 255)));

                        // 确保可见度
                        if (pixelValue < 50) {
                            pixelValue = 50; // 最暗不低于50
                        }
                    }

                    image.getRaster().setSample(x, y, 0, pixelValue);
                }
            }

            // 设置坐标系统为CGCS2000
            CoordinateReferenceSystem crs = CRS.decode("EPSG:4490", true);

            // 经纬度范围设置 - EPSG:4490是经纬度坐标系
            // 使用原点经纬度为(119.6906, 39.92551)，将点云坐标(米)转换为经纬度
            // 约0.00001度 ≈ 1米
            // 恢复原始经纬度关系，但进行适当调整确保地理参考正确
            double geoMinX = 119.6906 + minX * 0.00001; // 恢复最小经度
            double geoMaxX = 119.6906 + maxX * 0.00001; // 恢复最大经度
            double geoMinY = 39.92551 + minY * 0.00001; // 最小纬度
            double geoMaxY = 39.92551 + maxY * 0.00001; // 最大纬度

            // 输出坐标信息
            System.out.println("坐标系: " + crs.getName().toString());
            System.out.println("EPSG代码: " + CRS.lookupEpsgCode(crs, true));
            System.out.println("经纬度范围: 经度[" + geoMinX + "," + geoMaxX + "] 纬度[" + geoMinY + "," + geoMaxY + "]");

            // 创建地理范围对象 - 确保方向正确
            ReferencedEnvelope mapExtent = new ReferencedEnvelope(
                    geoMinX, geoMaxX, geoMinY, geoMaxY, crs);

            // 创建GridCoverage
            GridCoverageFactory factory = CoverageFactoryFinder.getGridCoverageFactory(null);
            GridCoverage2D coverage = factory.create("sample", image, mapExtent);

            // 设置GeoTIFF写入参数
            GeoTiffWriteParams writeParams = new GeoTiffWriteParams();
            writeParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParams.setCompressionType("LZW");
            writeParams.setCompressionQuality(0.75F);
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
                System.out.println("成功创建GeoTIFF文件: " + outputFile.getAbsolutePath());
                System.out.println("文件大小: " + outputFile.length() + " 字节");
                System.out.println("文件已生成，设置为EPSG:4490坐标系(CGCS2000)");
            } finally {
                writer.dispose();
            }

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("创建GeoTIFF文件失败: " + e.getMessage(), e);
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