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
import java.awt.Rectangle;
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
        if (pointList.isEmpty()) {
            throw new RuntimeException("点云数据为空");
        }

        // 创建一个BufferedImage来存储点云数据
        // 这里假设我们只处理前三个维度(X,Y,Z)，并将Z值作为颜色值
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        // 找出X,Y,Z值的最小和最大值用于归一化
        float minX = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = Float.MIN_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = Float.MIN_VALUE;

        for (Float[] point : pointList) {
            if (point != null && point.length >= 3) {
                float x = point[0];
                float y = point[1];
                float z = point[2];
                
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
            }
        }
        
        System.out.println("X范围: [" + minX + ", " + maxX + "]");
        System.out.println("Y范围: [" + minY + ", " + maxY + "]");
        System.out.println("Z范围: [" + minZ + ", " + maxZ + "]");

        // 处理所有点并统计写入的点数
        int pointsWritten = 0;
        for (int i = 0; i < pointList.size(); i++) {
            Float[] point = pointList.get(i);
            if (point == null || point.length < 3) {
                System.err.println("跳过无效点数据，索引: " + i);
                continue;
            }

            // 将点云坐标映射到图像坐标
            float x = (point[0] - minX) / (maxX - minX) * (width - 1);
            float y = (point[1] - minY) / (maxY - minY) * (height - 1);

            // 检查是否在图像范围内
            if (x >= 0 && x < width && y >= 0 && y < height) {
                // 归一化Z值到0-255范围
                float normalizedZ = (point[2] - minZ) / (maxZ - minZ);
                int zInt = Math.min(255, Math.max(0, (int) (normalizedZ * 255)));

                // 创建RGB颜色 - 这里简单地将Z值映射为灰度
                int rgb = (zInt << 16) | (zInt << 8) | zInt;
                image.setRGB((int) x, (int) y, rgb);
                pointsWritten++;
            } else {
                System.err.println("点数据超出图像范围，索引: " + i + ", x: " + x + ", y: " + y);
            }
        }
        System.out.println("成功写入点数: " + pointsWritten + "/" + pointList.size());
        if (pointsWritten == 0) {
            throw new RuntimeException("没有点数据被成功写入");
        }

        // 定义目标坐标系 (这里使用WGS84经纬度，可以根据需要修改)
        CoordinateReferenceSystem targetCRS = null;
        try {
            targetCRS = CRS.decode("EPSG:4326");
        } catch (FactoryException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        // 创建tiff生成参数并验证
        GeoTiffFormat format = new GeoTiffFormat();
        GeoTiffWriteParams wp = new GeoTiffWriteParams();
        wp.setCompressionMode(GeoTiffWriteParams.MODE_EXPLICIT);
        wp.setCompressionType("LZW");
        wp.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
        wp.setTiling(256, 256);
        
        ParameterValueGroup params = format.getWriteParameters();
        if (params == null) {
            throw new RuntimeException("无法获取GeoTIFF写入参数");
        }
        
        // 设置必要的参数
        params.parameter(AbstractGridFormat.GEOTOOLS_WRITE_PARAMS.getName().toString()).setValue(wp);
        
        // 设置压缩参数
        wp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        wp.setCompressionType("LZW");
        wp.setCompressionQuality(0.75f);
        
        // 设置分块参数
        wp.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
        wp.setTiling(256, 256);
        
        System.out.println("GeoTIFF写入参数已配置：");
        System.out.println("  Compression: " + wp.getCompressionType());
        System.out.println("  Tile size: " + wp.getTileWidth() + "x" + wp.getTileHeight());
        
        GeneralParameterValue[] writeParameters = params.values().toArray(new GeneralParameterValue[1]);
        
        if (writeParameters == null || writeParameters.length == 0) {
            throw new RuntimeException("GeoTIFF写入参数为空");
        }
        
        System.out.println("GeoTIFF写入参数已成功设置");

        // gridCoverage
        GeoTiffFormat format1 = new GeoTiffFormat();

        // 创建GeoTIFF写入器并确保资源正确释放
        File outputFile = new File("output.tif");
        GeoTiffWriter writer = null;
        try {
            writer = new GeoTiffWriter(outputFile);
            // 创建图像覆盖
            GridCoverage2D coverage = createGridCoverage(image);
            
            // 写入文件
            writer.write(coverage, writeParameters);
            System.out.println("文件大小: " + outputFile.length() + " bytes");
            System.out.println("TIFF文件已成功创建");
        } catch (IOException e) {
            System.err.println("文件写入失败: " + e.getMessage());
            throw new RuntimeException("文件写入失败", e);
        } finally {
            if (writer != null) {
                try {
                    writer.dispose();
                } catch (Exception e) {
                    System.err.println("关闭writer时出错: " + e.getMessage());
                }
            }
        }
    }

    private static GridCoverage2D createGridCoverage(BufferedImage image) {
        // 转换为灰度图像
        BufferedImage grayImage = new BufferedImage(
            image.getWidth(),
            image.getHeight(),
            BufferedImage.TYPE_BYTE_GRAY);
        
        Graphics g = grayImage.getGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        // 创建坐标参考系统
        CoordinateReferenceSystem crs = DefaultGeographicCRS.WGS84;

        // 创建仿射变换
        AffineTransform transform = new AffineTransform();
        transform.translate(0, grayImage.getHeight());
        transform.scale(1, -1);

        // 创建GridCoverageFactory
        GridCoverageFactory factory = CoverageFactoryFinder.getGridCoverageFactory(null);

        // 创建单波段SampleDimension
        GridSampleDimension sampleDimension = new GridSampleDimension("Intensity");

        // 创建GridCoverage2D并设置元数据
        Map<String, Object> properties = new HashMap<>();
        properties.put("name", "elevation");
        properties.put("description", "Point cloud elevation data");

        // 创建Envelope
        ReferencedEnvelope envelope = new ReferencedEnvelope(
            0, grayImage.getWidth(),
            0, grayImage.getHeight(),
            crs);

        // 创建GridGeometry
        GridGeometry2D gridGeometry = new GridGeometry2D(
            new GeneralGridEnvelope(new Rectangle(0, 0, grayImage.getWidth(), grayImage.getHeight())),
            envelope);

        // 创建并返回GridCoverage2D
        return factory.create(
            "elevation",
            grayImage,
            gridGeometry,
            new GridSampleDimension[]{sampleDimension},
            null,
            properties);
    }
}