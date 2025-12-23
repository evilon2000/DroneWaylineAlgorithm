package com.dji.zc.cloud.wayline.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.dji.zc.cloud.wayline.model.dto.DocxTableDTO;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Service
public class WordExportService {
    public byte[] generateInspectionReport(Map<String, String> data , MultipartFile file,List<DocxTableDTO> tableData) throws Exception {
        // 加载模板文件
        ClassPathResource resource = new ClassPathResource("巡检报告模版.docx");
        InputStream inputStream = resource.getInputStream();
        XWPFDocument document = new XWPFDocument(inputStream);

        // 替换占位符
        replacePlaceholders(document, data);

        // 插入动态表格到“样本类型及数量”部分
        insertTableAfterSampleType(document, tableData);

        insertImageAfterFlightPath(document, file);



        // 将文档写入字节数组
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.write(out);
        document.close();
        inputStream.close();
        return out.toByteArray();
    }


    private void insertImageAfterFlightPath(XWPFDocument document,MultipartFile file) throws Exception {
        // 查找“飞行路线：”段落
        XWPFParagraph flightPathParagraph = document.createParagraph();
        XWPFRun flightPathRun = flightPathParagraph.createRun();
        flightPathRun.setText("飞行路线：");
        flightPathRun.setFontFamily("FangSong");
        flightPathRun.setBold(true);
        flightPathRun.setFontSize(16);

        // 在其后创建图片段落
        XWPFParagraph imageParagraph = document.createParagraph();
        XWPFRun imageRun = imageParagraph.createRun();


        // 加载图片
        InputStream imageStream = file.getInputStream();
        // 添加图片（宽度 400pt，高度 300pt，约为 5.56 x 4.17 英寸）
        imageRun.addPicture(imageStream, XWPFDocument.PICTURE_TYPE_JPEG, "flight_path.jpg", 570 * 9525, 375 * 9525);
        imageStream.close();
    }


    private void replacePlaceholders(XWPFDocument document, Map<String, String> data) {
        // 遍历所有段落
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            String paragraphText = paragraph.getText();
            if (paragraphText.contains("${")) {
                for (Map.Entry<String, String> entry : data.entrySet()) {
                    String placeholder = "${" + entry.getKey() + "}";
                    paragraphText = paragraphText.replace(placeholder, entry.getValue());
                }
                // 清空段落内容并重新设置文本
                while (!paragraph.getRuns().isEmpty()) {
                    paragraph.removeRun(0);
                }
                XWPFRun run = paragraph.createRun();
                run.setText(paragraphText);
                run.setFontFamily("FangSong");
                run.setBold(true);
                run.setFontSize(16);
            }
        }
    }
    private void insertTableAfterSampleType(XWPFDocument document, List<DocxTableDTO> tableData) throws Exception {
        if(CollectionUtil.isEmpty(tableData)) {
            return;
        }
        int pos = 0;
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            if (paragraph.getText().trim().equals("样本类型及数量：")) {
                XWPFTable table = document.createTable();
                // 设置表格样式
                table.setWidth("100%");

                // 创建表头
                XWPFTableRow headerRow = table.getRow(0);
                if (headerRow == null) {
                    headerRow = table.createRow();
                }
                createTableCell(headerRow, 0, "序号");
                createTableCell(headerRow, 1, "样本");
                createTableCell(headerRow, 2, "类别");
                createTableCell(headerRow, 3, "数量");
                createTableCell(headerRow, 4, "占比");

                // 填充动态数据并合并单元格
                int currentRowIndex = 1; // 从第二行开始（第一行是表头）
                for (DocxTableDTO tableDTO : tableData) {
                    String index = tableDTO.getIndex();
                    String sample = tableDTO.getModelType();
                    List<DocxTableDTO.ModelContent> details = tableDTO.getModelContent();

                    // 合并单元格的行数等于 details 的数量
                    int rowSpan = details.size();

                    for (int i = 0; i < rowSpan; i++) {
                        XWPFTableRow row = table.createRow();
                        DocxTableDTO.ModelContent detail = details.get(i);

                        // 第一列：序号（仅在第一行填充，其余行留空以合并）
                        if (i == 0) {
                            XWPFTableCell indexCell = row.getCell(0);
                            createTableCell(row, 0, index);
                            setCellAlignment(indexCell);
                        } else {
                            createTableCell(row, 0, "");
                        }

                        // 第二列：样本（仅在第一行填充，其余行留空以合并）
                        if (i == 0) {
                            XWPFTableCell sampleCell = row.getCell(1);
                            createTableCell(row, 1, sample);
                            setCellAlignment(sampleCell);
                        } else {
                            createTableCell(row, 1, "");
                        }

                        // 第三列及之后：类别、数量、占比
                        createTableCell(row, 2, detail.getCls());
                        createTableCell(row, 3, detail.getCount().toString());
                        createTableCell(row, 4, detail.getPercent());
                    }

                    // 合并“序号”和“样本”列
                    if (rowSpan > 1) {
                        mergeCellsVertically(table, 0, currentRowIndex, currentRowIndex + rowSpan - 1); // 合并序号列
                        mergeCellsVertically(table, 1, currentRowIndex, currentRowIndex + rowSpan - 1); // 合并样本列
                    }

                    currentRowIndex += rowSpan;
                }
                break;
            }
            pos++;
        }
    }


    private void createTableCell(XWPFTableRow row, int cellIndex, String text) {
        XWPFTableCell cell = row.getCell(cellIndex);
        if (cell == null) {
            cell = row.createCell();
        }
        // 清空单元格内容
        while (!cell.getParagraphs().isEmpty()) {
            cell.removeParagraph(0);
        }
        XWPFParagraph paragraph = cell.addParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontFamily("FangSong");
        run.setFontSize(16);
    }

    private void setCellAlignment(XWPFTableCell indexCell) {
        if (indexCell != null) {
            // 设置垂直居中
            indexCell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);

            // 设置水平居中
            if (!indexCell.getParagraphs().isEmpty()) {
                XWPFParagraph paragraph = indexCell.getParagraphs().get(0);
                paragraph.setAlignment(ParagraphAlignment.CENTER);
            }
        }
    }

    private void mergeCellsVertically(XWPFTable table, int col, int fromRow, int toRow) {
        for (int rowIndex = fromRow; rowIndex <= toRow; rowIndex++) {
            XWPFTableCell cell = table.getRow(rowIndex).getCell(col);
            if (rowIndex == fromRow) {
                cell.getCTTc().addNewTcPr().addNewVMerge().setVal(STMerge.RESTART);
            } else {
                cell.getCTTc().addNewTcPr().addNewVMerge().setVal(STMerge.CONTINUE);
            }
        }
    }
}
