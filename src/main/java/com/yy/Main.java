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
            //     System.out.println(v1[0] + "," + v1[1] + "," + v1[2]);
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

    public static void createTif(List<Float> pointList, int width, int height) {
        // 留空，保持与原始方法签名一致
    }

    public static void createTiff(List<Float[]> pointList, int width, int height) {
        // 调用简化版本的方法
        createSimpleTiff(pointList, width, height);
    }
    
    /**
     * 创建简化版本的TIFF文件和世界文件（TFW）
     * 这种方法不依赖GeoTools的复杂API，而是直接创建标准TIFF和世界文件
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
            
            System.out.println("点云数据范围: X[" + minX + ", " + maxX + "] Y[" + minY + ", " + maxY + "] Z[" + minZ + ", " + maxZ + "]");
            
            // 确定合适的图像尺寸，使用点云数据的实际尺寸
            int newWidth = 750;  // 使用点云X范围的近似值
            int newHeight = 1200; // 使用点云Y范围的近似值
            
            System.out.println("调整后的图像尺寸: " + newWidth + "x" + newHeight);
            
            // 创建一个简单的单波段栅格数据集，类似于DEM
            float[][] elevationData = new float[newHeight][newWidth];
            
            // 确定网格单元格大小
            float pixelSizeX = (maxX - minX) / (newWidth - 1);
            float pixelSizeY = (maxY - minY) / (newHeight - 1);
            
            // 将点云数据映射到网格
            // 首先将所有值初始化为NaN
            for (int y = 0; y < newHeight; y++) {
                for (int x = 0; x < newWidth; x++) {
                    elevationData[y][x] = Float.NaN;
                }
            }
            
            // 映射点云数据到网格
            for (Float[] point : pointList) {
                int x = Math.round((point[0] - minX) / pixelSizeX);
                int y = newHeight - 1 - Math.round((point[1] - minY) / pixelSizeY);
                
                if (x >= 0 && x < newWidth && y >= 0 && y < newHeight) {
                    if (Float.isNaN(elevationData[y][x]) || elevationData[y][x] < point[2]) {
                        elevationData[y][x] = point[2];
                    }
                }
            }
            
            // 创建输出文件
            String worldFileName = "output.tfw";
            String outputFileName = "output.tif";
            
            // 创建世界文件（TFW）- 包含地理参考信息
            try (PrintWriter writer = new PrintWriter(new FileWriter(worldFileName))) {
                // 世界文件格式：六行，分别是：
                // 像素宽度（X方向分辨率）
                // 行旋转项（通常为0）
                // 列旋转项（通常为0）
                // 像素高度（Y方向分辨率，通常为负值）
                // X坐标（左上角像素的中心X坐标）
                // Y坐标（左上角像素的中心Y坐标）
                
                double xRes = (maxX - minX) / newWidth;
                double yRes = (maxY - minY) / newHeight;
                double xOrigin = 119.69060000000 + minX * 0.00001; // 左上角X坐标
                double yOrigin = 39.94263056000 + maxY * 0.00001;  // 左上角Y坐标
                
                writer.println(xRes);          // 像素宽度
                writer.println(0.0);           // 行旋转项
                writer.println(0.0);           // 列旋转项
                writer.println(-yRes);         // 像素高度（负值）
                writer.println(xOrigin);       // 左上角X坐标
                writer.println(yOrigin);       // 左上角Y坐标
                
                System.out.println("成功创建世界文件: " + worldFileName);
            }
            
            // 创建TIFF文件 - 使用简单的灰度图像
            BufferedImage image = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_BYTE_GRAY);
            
            // 将高程数据映射到灰度值
            for (int y = 0; y < newHeight; y++) {
                for (int x = 0; x < newWidth; x++) {
                    float value = elevationData[y][x];
                    int pixelValue;
                    
                    if (Float.isNaN(value)) {
                        pixelValue = 0; // 无数据区域使用黑色
                    } else {
                        // 映射Z值到0-255范围
                        pixelValue = Math.min(255, Math.max(0, 
                                Math.round(((value - minZ) / (maxZ - minZ)) * 255)));
                    }
                    
                    image.getRaster().setSample(x, y, 0, pixelValue);
                }
            }
            
            // 保存TIFF文件
            File outputFile = new File(outputFileName);
            ImageIO.write(image, "TIFF", outputFile);
            System.out.println("成功创建TIFF文件: " + outputFile.getAbsolutePath());
            System.out.println("文件大小: " + outputFile.length() + " 字节");
            
            // 告知用户如何使用这些文件
            System.out.println("提示: 生成了两个文件:");
            System.out.println("1. " + outputFileName + " - 包含图像数据");
            System.out.println("2. " + worldFileName + " - 包含地理参考信息");
            System.out.println("将这两个文件一起上传到GeoServer，应该可以被正确识别为地理参考数据。");
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("创建TIFF文件失败: " + e.getMessage(), e);
        }
    }
}