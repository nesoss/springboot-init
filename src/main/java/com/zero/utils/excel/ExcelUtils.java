package com.zero.utils.excel;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.hssf.usermodel.HSSFDataValidation;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.ClientAnchor.AnchorType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;

/**
 * Excel导入导出工具类
 *
 * @author sonata
 */
@Slf4j
public class ExcelUtils {

    private static final String XLSX = ".xlsx";
    private static final String XLS = ".xls";
    public static final String ROW_MERGE = "row_merge";
    public static final String COLUMN_MERGE = "column_merge";
    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final String ROW_NUM = "rowNum";
    private static final String ROW_DATA = "rowData";
    private static final String ROW_TIPS = "rowTips";
    private static final int CELL_OTHER = 0;
    private static final int CELL_ROW_MERGE = 1;
    private static final int CELL_COLUMN_MERGE = 2;
    private static final int IMG_HEIGHT = 30;
    private static final int IMG_WIDTH = 30;
    private static final char LEAN_LINE = '/';
    private static final int BYTES_DEFAULT_LENGTH = 10240;
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getNumberInstance();


    public static <T> List<T> readFile(File file, Class<T> clazz) throws Exception {
        JSONArray array = readFile(file);
        return getBeanList(array, clazz);
    }

    public static <T> List<T> readMultipartFile(MultipartFile mFile, Class<T> clazz) throws Exception {
        JSONArray array = readMultipartFile(mFile);
        return getBeanList(array, clazz);
    }

    public static JSONArray readFile(File file) throws Exception {
        return readExcel(null, file);
    }

    public static JSONArray readMultipartFile(MultipartFile mFile) throws Exception {
        return readExcel(mFile, null);
    }

    private static <T> List<T> getBeanList(JSONArray array, Class<T> clazz) throws Exception {
        List<T> list = new ArrayList<>();
        Map<Integer, String> uniqueMap = new HashMap<>(16);
        for (int i = 0; i < array.size(); i++) {
            list.add(getBean(clazz, array.getJSONObject(i), uniqueMap));
        }
        return list;
    }

    /**
     * 获取每个对象的数据
     */
    private static <T> T getBean(Class<T> c, JSONObject obj, Map<Integer, String> uniqueMap) throws Exception {
        T t = c.newInstance();
        // T t = c.getDeclaredConstructor().newInstance();
        Field[] fields = c.getDeclaredFields();
        List<String> errMsgList = new ArrayList<>();
        boolean hasRowTipsField = false;
        StringBuilder uniqueBuilder = new StringBuilder();
        int rowNum = 0;
        for (Field field : fields) {
            // 行号
            if (field.getName().equals(ROW_NUM)) {
                rowNum = obj.getInt(ROW_NUM);
                field.setAccessible(true);
                field.set(t, rowNum);
                continue;
            }
            // 是否需要设置异常信息
            if (field.getName().equals(ROW_TIPS)) {
                hasRowTipsField = true;
                continue;
            }
            // 原始数据
            if (field.getName().equals(ROW_DATA)) {
                field.setAccessible(true);
                field.set(t, obj.toString());
                continue;
            }
            // 设置对应属性值
            setFieldValue(t, field, obj, uniqueBuilder, errMsgList);
        }
        // 数据唯一性校验
        if (uniqueBuilder.length() > 0) {
            if (uniqueMap.containsValue(uniqueBuilder.toString())) {
                Set<Integer> rowNumKeys = uniqueMap.keySet();
                for (Integer num : rowNumKeys) {
                    if (uniqueMap.get(num).equals(uniqueBuilder.toString())) {
                        errMsgList.add(String.format("数据唯一性校验失败,(%s)与第%s行重复)", uniqueBuilder, num));
                    }
                }
            } else {
                uniqueMap.put(rowNum, uniqueBuilder.toString());
            }
        }
        // 失败处理
        if (errMsgList.isEmpty() && !hasRowTipsField) {
            return t;
        }
        StringBuilder sb = new StringBuilder();
        int size = errMsgList.size();
        for (int i = 0; i < size; i++) {
            if (i == size - 1) {
                sb.append(errMsgList.get(i));
            } else {
                sb.append(errMsgList.get(i)).append(";");
            }
        }
        // 设置错误信息
        for (Field field : fields) {
            if (field.getName().equals(ROW_TIPS)) {
                field.setAccessible(true);
                field.set(t, sb.toString());
            }
        }
        return t;
    }

    private static <T> void setFieldValue(T t, Field field, JSONObject obj, StringBuilder uniqueBuilder, List<String> errMsgList) {
        // 获取 ExcelImport 注解属性
        ExcelImport annotation = field.getAnnotation(ExcelImport.class);
        if (annotation == null) {
            return;
        }
        String cname = annotation.value();
        if (cname.trim().isEmpty()) {
            return;
        }
        // 获取具体值
        String val = null;
        if (obj.containsKey(cname)) {
            val = getString(obj.getStr(cname));
        }
        if (val == null) {
            return;
        }
        field.setAccessible(true);
        // 判断是否必填
        boolean require = annotation.required();
        if (require && val.isEmpty()) {
            errMsgList.add(String.format("[%s]不能为空", cname));
            return;
        }
        // 数据唯一性获取
        boolean unique = annotation.unique();
        if (unique) {
            if (uniqueBuilder.length() > 0) {
                uniqueBuilder.append("--").append(val);
            } else {
                uniqueBuilder.append(val);
            }
        }
        // 判断是否超过最大长度
        int maxLength = annotation.maxLength();
        if (maxLength > 0 && val.length() > maxLength) {
            errMsgList.add(String.format("[%s]长度不能超过%s个字符(当前%s个字符)", cname, maxLength, val.length()));
        }
        // 判断当前属性是否有映射关系
        LinkedHashMap<String, String> kvMap = getKvMap(annotation.kv());
        if (!kvMap.isEmpty()) {
            boolean isMatch = false;
            for (String key : kvMap.keySet()) {
                if (kvMap.get(key).equals(val)) {
                    val = key;
                    isMatch = true;
                    break;
                }
            }
            if (!isMatch) {
                errMsgList.add(String.format("[%s]的值不正确(当前值为%s)", cname, val));
                return;
            }
        }
        // 其余情况根据类型赋值
        String fieldClassName = field.getType().getSimpleName();
        try {
            if ("String".equalsIgnoreCase(fieldClassName)) {
                field.set(t, val);
            } else if ("boolean".equalsIgnoreCase(fieldClassName)) {
                field.set(t, Boolean.valueOf(val));
            } else if ("int".equalsIgnoreCase(fieldClassName) || "Integer".equals(fieldClassName)) {
                try {
                    field.set(t, Integer.valueOf(val));
                } catch (NumberFormatException e) {
                    errMsgList.add(String.format("[%s]的值格式不正确(当前值为%s)", cname, val));
                }
            } else if ("double".equalsIgnoreCase(fieldClassName)) {
                field.set(t, Double.valueOf(val));
            } else if ("long".equalsIgnoreCase(fieldClassName)) {
                field.set(t, Long.valueOf(val));
            } else if ("BigDecimal".equalsIgnoreCase(fieldClassName)) {
                field.set(t, new BigDecimal(val));
            }
        } catch (Exception e) {
            log.error("ERROR:", e);
        }
    }

    private static JSONArray readExcel(MultipartFile mFile, File file) {
        boolean fileNotExist = (file == null || !file.exists());
        if (mFile == null && fileNotExist) {
            return new JSONArray();
        }
        // 解析表格数据
        InputStream in = null;
        String fileName;
        Workbook book = null;
        JSONArray array = new JSONArray();
        try {
            if (mFile != null) {
                // 上传文件解析
                in = mFile.getInputStream();
                fileName = getString(mFile.getOriginalFilename()).toLowerCase();
            } else {
                // 本地文件解析
                in = new FileInputStream(file);
                fileName = file.getName().toLowerCase();
            }
            if (fileName.endsWith(XLSX)) {
                book = new XSSFWorkbook(in);
            } else if (fileName.endsWith(XLS)) {
                POIFSFileSystem poifsFileSystem = new POIFSFileSystem(in);
                book = new HSSFWorkbook(poifsFileSystem);
            } else {
                return new JSONArray();
            }
            array = read(book);
        } catch (IOException e) {
            log.error("ERROR:", e);
        } finally {
            try {
                if (book != null) {
                    book.close();
                }
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                log.error("ERROR:", e);
            }
        }
        return array;
    }

    private static JSONArray read(Workbook book) {
        // 获取 Excel 文件第一个 Sheet 页面
        Sheet sheet = book.getSheetAt(0);
        return readSheet(sheet);
    }

    private static JSONArray readSheet(Sheet sheet) {
        // 首行下标
        int rowStart = sheet.getFirstRowNum();
        // 尾行下标
        int rowEnd = sheet.getLastRowNum();
        // 获取表头行
        Row headRow = sheet.getRow(rowStart);
        if (headRow == null) {
            return new JSONArray();
        }
        int cellStart = headRow.getFirstCellNum();
        int cellEnd = headRow.getLastCellNum();
        Map<Integer, String> keyMap = new HashMap<>();
        for (int j = cellStart; j < cellEnd; j++) {
            // 获取表头数据
            String val = getCellValue(headRow.getCell(j));
            if (val != null && !val.trim().isEmpty()) {
                keyMap.put(j, val);
            }
        }
        // 如果表头没有数据则不进行解析
        if (keyMap.isEmpty()) {
            return (JSONArray) Collections.emptyList();
        }
        // 获取每行JSON对象的值
        JSONArray array = new JSONArray();
        // 如果首行与尾行相同，表明只有一行，返回表头数据
        if (rowStart == rowEnd) {
            JSONObject obj = new JSONObject();
            // 添加行号
            obj.put(ROW_NUM, 1);
            for (int i : keyMap.keySet()) {
                obj.put(keyMap.get(i), "");
            }
            array.add(obj);
            return array;
        }
        for (int i = rowStart + 1; i <= rowEnd; i++) {
            Row eachRow = sheet.getRow(i);
            JSONObject obj = new JSONObject();
            // 添加行号
            obj.put(ROW_NUM, i + 1);
            StringBuilder sb = new StringBuilder();
            for (int k = cellStart; k < cellEnd; k++) {
                if (eachRow != null) {
                    String val = getCellValue(eachRow.getCell(k));
                    // 所有数据添加到里面，用于判断该行是否为空
                    sb.append(val);
                    obj.put(keyMap.get(k), val);
                }
            }
            if (sb.length() > 0) {
                array.add(obj);
            }
        }
        return array;
    }

    private static String getCellValue(Cell cell) {
        // 空白或空
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        // String类型
        if (cell.getCellType() == CellType.STRING) {
            String val = cell.getStringCellValue();
            if (val == null || val.trim().length() == 0) {
                return "";
            }
            return val.trim();
        }
        // 数字类型
        if (cell.getCellType() == CellType.NUMERIC) {
            // 科学计数法类型
            return NUMBER_FORMAT.format(cell.getNumericCellValue()) + "";
        }
        // 布尔值类型
        if (cell.getCellType() == CellType.BOOLEAN) {
            return cell.getBooleanCellValue() + "";
        }
        // 错误类型
        return cell.getCellFormula();
    }

    public static <T> void exportTemplate(HttpServletResponse response, String fileName, Class<T> clazz) {
        exportTemplate(response, fileName, fileName, clazz, false);
    }

    public static <T> void exportTemplate(HttpServletResponse response, String fileName, String sheetName,
                                          Class<T> clazz) {
        exportTemplate(response, fileName, sheetName, clazz, false);
    }

    public static <T> void exportTemplate(HttpServletResponse response, String fileName, Class<T> clazz,
                                          boolean isContainExample) {
        exportTemplate(response, fileName, fileName, clazz, isContainExample);
    }

    public static <T> void exportTemplate(HttpServletResponse response, String fileName, String sheetName,
                                          Class<T> clazz, boolean isContainExample) {
        // 获取表头字段
        List<ExcelClassField> headFieldList = getExcelClassFieldList(clazz);
        // 获取表头数据和示例数据
        List<List<Object>> sheetDataList = new ArrayList<>();
        List<Object> headList = new ArrayList<>();
        List<Object> exampleList = new ArrayList<>();
        Map<Integer, List<String>> selectMap = new LinkedHashMap<>();
        for (int i = 0; i < headFieldList.size(); i++) {
            ExcelClassField each = headFieldList.get(i);
            headList.add(each.getName());
            exampleList.add(each.getExample());
            LinkedHashMap<String, String> kvMap = each.getKvMap();
            if (kvMap != null && !kvMap.isEmpty()) {
                selectMap.put(i, new ArrayList<>(kvMap.values()));
            }
        }
        sheetDataList.add(headList);
        if (isContainExample) {
            sheetDataList.add(exampleList);
        }
        // 导出数据
        export(response, fileName, sheetName, sheetDataList, selectMap);
    }

    private static <T> List<ExcelClassField> getExcelClassFieldList(Class<T> clazz) {
        // 解析所有字段
        Field[] fields = clazz.getDeclaredFields();
        boolean hasExportAnnotation = false;
        Map<Integer, List<ExcelClassField>> map = new LinkedHashMap<>();
        List<Integer> sortList = new ArrayList<>();
        for (Field field : fields) {
            ExcelClassField cf = getExcelClassField(field);
            if (cf.getHasAnnotation() == 1) {
                hasExportAnnotation = true;
            }
            int sort = cf.getSort();
            if (map.containsKey(sort)) {
                map.get(sort).add(cf);
            } else {
                List<ExcelClassField> list = new ArrayList<>();
                list.add(cf);
                sortList.add(sort);
                map.put(sort, list);
            }
        }
        Collections.sort(sortList);
        // 获取表头
        List<ExcelClassField> headFieldList = new ArrayList<>();
        if (hasExportAnnotation) {
            for (Integer sort : sortList) {
                for (ExcelClassField cf : map.get(sort)) {
                    if (cf.getHasAnnotation() == 1) {
                        headFieldList.add(cf);
                    }
                }
            }
        } else {
            headFieldList.addAll(map.get(0));
        }
        return headFieldList;
    }

    private static ExcelClassField getExcelClassField(Field field) {
        ExcelClassField cf = new ExcelClassField();
        String fieldName = field.getName();
        cf.setFieldName(fieldName);
        ExcelExport annotation = field.getAnnotation(ExcelExport.class);
        // 无 ExcelExport 注解情况
        if (annotation == null) {
            cf.setHasAnnotation(0);
            cf.setName(fieldName);
            cf.setSort(0);
            return cf;
        }
        // 有 ExcelExport 注解情况
        cf.setHasAnnotation(1);
        cf.setName(annotation.value());
        String example = getString(annotation.example());
        if (!example.isEmpty()) {
            if (isNumeric(example)) {
                cf.setExample(Double.valueOf(example));
            } else {
                cf.setExample(example);
            }
        } else {
            cf.setExample("");
        }
        cf.setSort(annotation.sort());
        // 解析映射
        String kv = getString(annotation.kv());
        cf.setKvMap(getKvMap(kv));
        return cf;
    }

    private static LinkedHashMap<String, String> getKvMap(String kv) {
        LinkedHashMap<String, String> kvMap = new LinkedHashMap<>();
        if (kv.isEmpty()) {
            return kvMap;
        }
        String[] kvs = kv.split(";");
        if (kvs.length == 0) {
            return kvMap;
        }
        for (String each : kvs) {
            String[] eachKv = getString(each).split("-");
            if (eachKv.length != 2) {
                continue;
            }
            String k = eachKv[0];
            String v = eachKv[1];
            if (k.isEmpty() || v.isEmpty()) {
                continue;
            }
            kvMap.put(k, v);
        }
        return kvMap;
    }

    /**
     * 导出表格到本地
     *
     * @param file      本地文件对象
     * @param sheetData 导出数据
     */
    public static void exportFile(File file, List<List<Object>> sheetData) {
        if (file == null) {
            log.info("文件创建失败");
            return;
        }
        if (sheetData == null) {
            sheetData = new ArrayList<>();
        }
        export(null, file, file.getName(), file.getName(), sheetData, null);
    }

    /**
     * 导出表格到本地
     *
     * @param <T>      导出数据类似，和K类型保持一致
     * @param filePath 文件父路径（如：D:/doc/excel/）
     * @param fileName 文件名称（不带尾缀，如：学生表）
     * @param list     导出数据
     * @throws IOException IO异常
     */
    public static <T> File exportFile(String filePath, String fileName, List<T> list) throws IOException {
        File file = getFile(filePath, fileName);
        List<List<Object>> sheetData = getSheetData(list);
        exportFile(file, sheetData);
        return file;
    }

    /**
     * 获取文件
     *
     * @param filePath filePath 文件父路径（如：D:/doc/excel/）
     * @param fileName 文件名称（不带尾缀，如：用户表）
     * @return 本地File文件对象
     */
    private static File getFile(String filePath, String fileName) throws IOException {
        String dirPath = getString(filePath);
        String fileFullPath;
        if (dirPath.isEmpty()) {
            fileFullPath = fileName;
        } else {
            // 判定文件夹是否存在，如果不存在，则级联创建
            File dirFile = new File(dirPath);
            if (!dirFile.exists()) {
                dirFile.mkdirs();
            }
            // 获取文件夹全名
            if (dirPath.endsWith(String.valueOf(LEAN_LINE))) {
                fileFullPath = dirPath + fileName + XLSX;
            } else {
                fileFullPath = dirPath + LEAN_LINE + fileName + XLSX;
            }
        }
        log.info(fileFullPath);
        File file = new File(fileFullPath);
        if (!file.exists()) {
            file.createNewFile();
        }
        return file;
    }

    private static <T> List<List<Object>> getSheetData(List<T> list) {
        // 获取表头字段
        List<ExcelClassField> excelClassFieldList = getExcelClassFieldList(list.get(0).getClass());
        List<String> headFieldList = new ArrayList<>();
        List<Object> headList = new ArrayList<>();
        Map<String, ExcelClassField> headFieldMap = new HashMap<>();
        for (ExcelClassField each : excelClassFieldList) {
            String fieldName = each.getFieldName();
            headFieldList.add(fieldName);
            headFieldMap.put(fieldName, each);
            headList.add(each.getName());
        }
        // 添加表头名称
        List<List<Object>> sheetDataList = new ArrayList<>();
        sheetDataList.add(headList);
        // 获取表数据
        for (T t : list) {
            Map<String, Object> fieldDataMap = getFieldDataMap(t);
            Set<String> fieldDataKeys = fieldDataMap.keySet();
            List<Object> rowList = new ArrayList<>();
            for (String headField : headFieldList) {
                if (!fieldDataKeys.contains(headField)) {
                    continue;
                }
                Object data = fieldDataMap.get(headField);
                if (data == null) {
                    rowList.add("");
                    continue;
                }
                ExcelClassField cf = headFieldMap.get(headField);
                // 判断是否有映射关系
                LinkedHashMap<String, String> kvMap = cf.getKvMap();
                if (kvMap == null || kvMap.isEmpty()) {
                    rowList.add(data);
                    continue;
                }
                String val = kvMap.get(data.toString());
                if (isNumeric(val)) {
                    rowList.add(Double.valueOf(val));
                } else {
                    rowList.add(val);
                }
            }
            sheetDataList.add(rowList);
        }
        return sheetDataList;
    }

    private static <T> Map<String, Object> getFieldDataMap(T t) {
        Map<String, Object> map = new HashMap<>();
        Field[] fields = t.getClass().getDeclaredFields();
        try {
            for (Field field : fields) {
                String fieldName = field.getName();
                field.setAccessible(true);
                Object object = field.get(t);
                map.put(fieldName, object);
            }
        } catch (IllegalArgumentException | IllegalAccessException e) {
            log.error("ERROR:", e);
        }
        return map;
    }

    public static void exportEmpty(HttpServletResponse response, String fileName) {
        List<List<Object>> sheetDataList = new ArrayList<>();
        List<Object> headList = new ArrayList<>();
        headList.add("导出无数据");
        sheetDataList.add(headList);
        export(response, fileName, sheetDataList);
    }

    public static void export(HttpServletResponse response, String fileName, List<List<Object>> sheetDataList) {
        export(response, fileName, fileName, sheetDataList, null);
    }

    public static void export(HttpServletResponse response, String fileName, String sheetName,
                              List<List<Object>> sheetDataList) {
        export(response, fileName, sheetName, sheetDataList, null);
    }

    public static void export(HttpServletResponse response, String fileName, String sheetName,
                              List<List<Object>> sheetDataList, Map<Integer, List<String>> selectMap) {
        export(response, null, fileName, sheetName, sheetDataList, selectMap);
    }

    public static <T, K> void export(HttpServletResponse response, String fileName, List<T> list, Class<K> template) {
        // 参数校验
        if (response == null || fileName == null || fileName.trim().isEmpty()) {
            // 日志记录错误，或者抛出一个异常
            log.error("Response or fileName is null or empty.");
            return;
        }
        // 判断是否需要导出空文件
        boolean listIsEmpty = (list == null || list.isEmpty());
        if (template == null && listIsEmpty) {
            exportEmpty(response, fileName);
            return;
        }
        // 优化判断逻辑，直接进行操作而不是多次判断listIsEmpty
        if (listIsEmpty) {
            // 导出模板数据
            exportTemplate(response, fileName, template);
        } else {
            // 导出数据
            try {
                List<List<Object>> sheetDataList = getSheetData(list);
                export(response, fileName, sheetDataList);
            } catch (Exception e) {
                // 日志记录异常，或者向客户端发送错误响应
                log.error("Error exporting data", e);
            }
        }
//        // 如果 list 数据，则导出模板数据
//        if (listIsEmpty) {
//            exportTemplate(response, fileName, template);
//            return;
//        }
        // 导出数据
//        List<List<Object>> sheetDataList = getSheetData(list);
//        export(response, fileName, sheetDataList);
    }

    public static void export(HttpServletResponse response, String fileName, List<List<Object>> sheetDataList, Map<Integer, List<String>> selectMap) {
        export(response, fileName, fileName, sheetDataList, selectMap);
    }

    private static void export(HttpServletResponse response, File file, String fileName, String sheetName,
                               List<List<Object>> sheetDataList, Map<Integer, List<String>> selectMap) {
        // 整个 Excel 表格 book 对象
        SXSSFWorkbook book = new SXSSFWorkbook();
        // 每个 Sheet 页
        Sheet sheet = book.createSheet(sheetName);
        Drawing<?> patriarch = sheet.createDrawingPatriarch();
        // 设置表头背景色（灰色）
        CellStyle headStyle = book.createCellStyle();
        headStyle.setFillForegroundColor(IndexedColors.GREY_80_PERCENT.index);
        headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headStyle.setAlignment(HorizontalAlignment.CENTER);
        headStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.index);
        // 设置表身背景色（默认色）
        CellStyle rowStyle = book.createCellStyle();
        rowStyle.setAlignment(HorizontalAlignment.CENTER);
        rowStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        // 设置表格列宽度（默认为20个字节）
        sheet.setDefaultColumnWidth(20);
        // 创建合并算法数组
        int rowLength = sheetDataList.size();
        int columnLength = sheetDataList.get(0).size();
        int[][] mergeArray = new int[rowLength][columnLength];
        for (int i = 0; i < sheetDataList.size(); i++) {
            // 每个 Sheet 页中的行数据
            Row row = sheet.createRow(i);
            List<Object> rowList = sheetDataList.get(i);
            for (int j = 0; j < rowList.size(); j++) {
                // 每个行数据中的单元格数据
                Object o = rowList.get(j);
                int v = 0;
                if (o instanceof URL) {
                    // 如果要导出图片的话, 链接需要传递 URL 对象
                    setCellPicture(book, row, patriarch, i, j, (URL) o);
                } else {
                    Cell cell = row.createCell(j);
                    if (i == 0) {
                        // 第一行为表头行，采用灰色底背景
                        v = setCellValue(cell, o, headStyle);
                    } else {
                        // 其他行为数据行，默认白底色
                        v = setCellValue(cell, o, rowStyle);
                    }
                }
                mergeArray[i][j] = v;
            }
        }
        // 合并单元格
        mergeCells(sheet, mergeArray);
        // 设置下拉列表
        setSelect(sheet, selectMap);
        // 写数据
        if (response != null) {
            // 前端导出
            try {
                write(response, book, fileName);
            } catch (IOException e) {
                log.error("ERROR:", e);
            }
        } else {
            // 本地导出
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(file);
                ByteArrayOutputStream ops = new ByteArrayOutputStream();
                book.write(ops);
                fos.write(ops.toByteArray());
            } catch (Exception e) {
                log.error("ERROR:", e);
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException e) {
                        log.error("ERROR:", e);
                    }

                }
            }
        }
    }

    /**
     * 合并当前Sheet页的单元格
     *
     * @param sheet      当前 sheet 页
     * @param mergeArray 合并单元格算法
     */
    private static void mergeCells(Sheet sheet, int[][] mergeArray) {
        // 横向合并
        for (int x = 0; x < mergeArray.length; x++) {
            int[] arr = mergeArray[x];
            boolean merge = false;
            int y1 = 0;
            int y2 = 0;
            for (int y = 0; y < arr.length; y++) {
                int value = arr[y];
                if (value == CELL_COLUMN_MERGE) {
                    if (!merge) {
                        y1 = y;
                    }
                    y2 = y;
                    merge = true;
                } else {
                    merge = false;
                    if (y1 > 0) {
                        sheet.addMergedRegion(new CellRangeAddress(x, x, (y1 - 1), y2));
                    }
                    y1 = 0;
                    y2 = 0;
                }
            }
            if (y1 > 0) {
                sheet.addMergedRegion(new CellRangeAddress(x, x, (y1 - 1), y2));
            }
        }
        // 纵向合并
        int xLen = mergeArray.length;
        int yLen = mergeArray[0].length;
        for (int y = 0; y < yLen; y++) {
            boolean merge = false;
            int x1 = 0;
            int x2 = 0;
            for (int x = 0; x < xLen; x++) {
                int value = mergeArray[x][y];
                if (value == CELL_ROW_MERGE) {
                    if (!merge) {
                        x1 = x;
                    }
                    x2 = x;
                    merge = true;
                } else {
                    merge = false;
                    if (x1 > 0) {
                        sheet.addMergedRegion(new CellRangeAddress((x1 - 1), x2, y, y));
                    }
                    x1 = 0;
                    x2 = 0;
                }
            }
            if (x1 > 0) {
                sheet.addMergedRegion(new CellRangeAddress((x1 - 1), x2, y, y));
            }
        }
    }

    private static void write(HttpServletResponse response, SXSSFWorkbook book, String fileName) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String name = new String(fileName.getBytes("GBK"), "ISO8859_1") + XLSX;
        response.addHeader("Content-Disposition", "attachment;filename=" + name);
        ServletOutputStream out = response.getOutputStream();
        book.write(out);
        out.flush();
        out.close();
    }

    private static int setCellValue(Cell cell, Object o, CellStyle style) {
        // 设置样式
        cell.setCellStyle(style);
        // 数据为空时
        if (o == null || "".equals(o.toString())) {
            cell.setCellType(CellType.STRING);
            cell.setCellValue("");
            return CELL_OTHER;
        }
        // 是否为字符串
        if (o instanceof String) {
            String s = o.toString();
            if (isNumeric(s)) {
                cell.setCellType(CellType.NUMERIC);
                BigDecimal bigDecimal = new BigDecimal(s);
                String out = bigDecimal.toPlainString();
//                cell.setCellValue(Double.parseDouble(s));
                cell.setCellValue(out);
                return CELL_OTHER;
            } else {
                cell.setCellType(CellType.STRING);
                cell.setCellValue(s);
            }
            if (s.equals(ROW_MERGE)) {
                return CELL_ROW_MERGE;
            } else if (s.equals(COLUMN_MERGE)) {
                return CELL_COLUMN_MERGE;
            } else {
                return CELL_OTHER;
            }
        }
        // 是否为字符串
        if (o instanceof Integer || o instanceof Long || o instanceof Double || o instanceof Float) {
            cell.setCellType(CellType.NUMERIC);
            cell.setCellValue(Double.parseDouble(o.toString()));
            return CELL_OTHER;
        }
        // 是否为Boolean
        if (o instanceof Boolean) {
            cell.setCellType(CellType.BOOLEAN);
            cell.setCellValue((Boolean) o);
            return CELL_OTHER;
        }
        // 如果是BigDecimal，则默认3位小数
        if (o instanceof BigDecimal) {
            cell.setCellType(CellType.NUMERIC);
            cell.setCellValue(((BigDecimal) o).setScale(3, RoundingMode.HALF_UP).doubleValue());
            return CELL_OTHER;
        }
        // 如果是Date数据，则显示格式化数据
        if (o instanceof Date) {
            cell.setCellType(CellType.STRING);
            cell.setCellValue(formatDate((Date) o));
            return CELL_OTHER;
        }
        // 如果是其他，则默认字符串类型
        cell.setCellType(CellType.STRING);
        cell.setCellValue(o.toString());
        return CELL_OTHER;
    }

    private static void setCellPicture(SXSSFWorkbook wb, Row sr, Drawing<?> patriarch, int x, int y, URL url) {
        // 设置图片宽高
        sr.setHeight((short) (IMG_WIDTH * IMG_HEIGHT));
        // （jdk1.7版本try中定义流可自动关闭）
        try (InputStream is = url.openStream(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buff = new byte[BYTES_DEFAULT_LENGTH];
            int rc;
            while ((rc = is.read(buff, 0, BYTES_DEFAULT_LENGTH)) > 0) {
                outputStream.write(buff, 0, rc);
            }
            // 设置图片位置
            XSSFClientAnchor anchor = new XSSFClientAnchor(0, 0, 0, 0, y, x, y + 1, x + 1);
            // 设置这个，图片会自动填满单元格的长宽
            anchor.setAnchorType(AnchorType.MOVE_AND_RESIZE);
            patriarch.createPicture(anchor, wb.addPicture(outputStream.toByteArray(), HSSFWorkbook.PICTURE_TYPE_JPEG));
        } catch (Exception e) {
            log.error("ERROR:", e);
        }
    }

    // region 验证导入文件的表头是否正确
    public static boolean validateHeader(InputStream inputStream, String[] header) throws IOException {
        if (!inputStream.markSupported()) {
            inputStream = new PushbackInputStream(inputStream, 8);
        }
        return validateHeader2003(inputStream, header);
//        if (new POIFSFileSystem().hasPOIFSHeader(inputStream)) {
//            return validateHeader2003(inputStream, header);
//        } else {
//            return validateHeader2007(inputStream, header);
//        }
    }

    public static boolean validateHeader2003(InputStream inputStream, String[] header) throws IOException {
        HSSFWorkbook sheets = new HSSFWorkbook(inputStream);
        HSSFSheet sheet = sheets.getSheetAt(0);
        HSSFRow rowHeader = sheet.getRow(0);
        if (sheet.getPhysicalNumberOfRows() == 0) {
            return false;
        }
        int celllen = rowHeader.getPhysicalNumberOfCells();
        int cellindex = 0, headerindex = 0;
        for (; cellindex < celllen && headerindex < header.length; cellindex++, headerindex++) {
            if (!rowHeader.getCell(cellindex).toString().equals(header[headerindex])) {
                break;
            }
        }
        if (cellindex == headerindex && cellindex == celllen && headerindex == header.length) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean validateHeader2007(InputStream inputStream, String[] header) throws IOException {
        XSSFWorkbook sheets = new XSSFWorkbook(inputStream);
        XSSFSheet sheet = sheets.getSheetAt(0);
        if (sheet.getPhysicalNumberOfRows() == 0) {
            return false;
        }
        XSSFRow rowHeader = sheet.getRow(0);
        int celllen = rowHeader.getPhysicalNumberOfCells();
        int cellindex = 0, headerindex = 0;
        for (; cellindex < celllen && headerindex < header.length; cellindex++, headerindex++) {
            if (!rowHeader.getCell(cellindex).toString().equals(header[headerindex])) {
                break;
            }
        }
        if (cellindex == headerindex && cellindex == celllen && headerindex == header.length) {
            return true;
        } else {
            return false;
        }
    }

    private static String formatDate(Date date) {
        if (date == null) {
            return "";
        }
        SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT);
        return format.format(date);
    }

    private static void setSelect(Sheet sheet, Map<Integer, List<String>> selectMap) {
        if (selectMap == null || selectMap.isEmpty()) {
            return;
        }
        Set<Entry<Integer, List<String>>> entrySet = selectMap.entrySet();
        for (Entry<Integer, List<String>> entry : entrySet) {
            int y = entry.getKey();
            List<String> list = entry.getValue();
            if (list == null || list.isEmpty()) {
                continue;
            }
            String[] arr = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                arr[i] = list.get(i);
            }
            DataValidationHelper helper = sheet.getDataValidationHelper();
            CellRangeAddressList addressList = new CellRangeAddressList(1, 65000, y, y);
            DataValidationConstraint dvc = helper.createExplicitListConstraint(arr);
            DataValidation dv = helper.createValidation(dvc, addressList);
            if (dv instanceof HSSFDataValidation) {
                dv.setSuppressDropDownArrow(false);
            } else {
                dv.setSuppressDropDownArrow(true);
                dv.setShowErrorBox(true);
            }
            sheet.addValidationData(dv);
        }
    }

    private static boolean isNumeric(String str) {
        if (StringUtils.isEmpty(str)) {
            return false;
        }

        if ("0.0".equals(str)) {
            return true;
        }
        for (int i = str.length(); --i >= 0; ) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String getString(String s) {
        if (s == null) {
            return "";
        }
        if (s.isEmpty()) {
            return s;
        }
        return s.trim();
    }

}
