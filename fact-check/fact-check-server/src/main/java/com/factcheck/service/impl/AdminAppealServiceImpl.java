package com.factcheck.service.impl;

import com.factcheck.constant.MessageConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.dto.AdminAppealHandleDTO;
import com.factcheck.dto.AdminAppealPageQueryDTO;
import com.factcheck.entity.Appeal;
import com.factcheck.entity.ConclusionRevision;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.entity.Message;
import com.factcheck.entity.OperationLog;
import com.factcheck.exception.AppealNotFoundException;
import com.factcheck.exception.BaseException;
import com.factcheck.exception.RecordNotFoundException;
import com.factcheck.mapper.AppealMapper;
import com.factcheck.mapper.ConclusionRevisionMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.MessageMapper;
import com.factcheck.mapper.OperationLogMapper;
import com.factcheck.result.PageResult;
import com.factcheck.service.AdminAppealService;
import com.factcheck.util.ValidationUtils;
import com.factcheck.vo.AppealVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 管理员申诉处理服务实现
 */
@Service
@Slf4j
public class AdminAppealServiceImpl implements AdminAppealService {

    @Autowired
    private AppealMapper appealMapper;
    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;
    @Autowired
    private ConclusionRevisionMapper conclusionRevisionMapper;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private OperationLogMapper operationLogMapper;

    /**
     * 分页查询申诉
     */
    @Override
    public PageResult page(AdminAppealPageQueryDTO pageQueryDTO) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员分页查询申诉：adminId={}, status={}", adminId, pageQueryDTO.getStatus());
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        int pageNum = pageQueryDTO.getPage() == null ? 1 : pageQueryDTO.getPage();
        int pageSize = pageQueryDTO.getPageSize() == null ? 10 : pageQueryDTO.getPageSize();
        PageHelper.startPage(pageNum, pageSize);
        List<Appeal> list = appealMapper.page(pageQueryDTO.getStatus());
        Page<Appeal> pageInfo = (Page<Appeal>) list;
        List<AppealVO> records = list.stream().map(this::toVO).collect(Collectors.toList());
        return new PageResult(pageInfo.getTotal(), records);
    }

    /**
     * 处理申诉（采纳/驳回）
     */
    @Override
    @Transactional
    public void handle(Long appealId, AdminAppealHandleDTO handleDTO) {
        // 空值保护（P5-72）：经 Controller 不可达（请求体必填），但内部调用或测试传 null 时
        // 会在下面的 getAccept() 直接 NPE，报错信息毫无指向性
        if (handleDTO == null) {
            throw new BaseException("处理参数不能为空");
        }
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员处理申诉：appealId={}, adminId={}, accept={}", appealId, adminId, handleDTO.getAccept());
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }

        // 先规范化文本字段：去掉首尾空白后再走校验，
        // 这样纯空白（"   "）会被必填校验拦下，也不会把空白写进结论修订与站内信。
        // 原先用 StringUtils.hasLength（不 trim），"   " 可以绕过必填校验。
        if (handleDTO.getNewReasoningChain() != null) {
            handleDTO.setNewReasoningChain(handleDTO.getNewReasoningChain().trim());
        }
        if (handleDTO.getHandleResult() != null) {
            handleDTO.setHandleResult(handleDTO.getHandleResult().trim());
        }

        Appeal appeal = appealMapper.getById(appealId);
        if (appeal == null) {
            throw new AppealNotFoundException(MessageConstant.APPEAL_NOT_FOUND);
        }
        if (appeal.getStatus() == null || appeal.getStatus() != 0) {
            throw new BaseException("该申诉已处理，请勿重复操作");
        }

        boolean accept = handleDTO.getAccept() != null && handleDTO.getAccept();
        if (accept) {
            handleAccept(appeal, handleDTO, adminId);
        } else {
            handleReject(appeal, handleDTO, adminId);
        }
    }

    /**
     * 采纳申诉：修改结论并附原因
     */
    private void handleAccept(Appeal appeal, AdminAppealHandleDTO handleDTO, Long adminId) {
        if (handleDTO.getNewVerdict() == null || (handleDTO.getNewVerdict() != 1
                && handleDTO.getNewVerdict() != 2 && handleDTO.getNewVerdict() != 3)) {
            throw new BaseException("采纳申诉时必须给出新结论（1真/2假/3无法判定）");
        }
        // 文本字段校验（P2-17）：非空 + 长度。
        // 推理依据入库到 TEXT 列，但仍限制在 2000 字以内，避免把超长文本写进结论修订与站内信；
        // 驳回原因列为 VARCHAR(500)，超长会在写库时才报数据完整性错误。
        ValidationUtils.requireText(handleDTO.getNewReasoningChain(), "新的推理依据", 2000);

        FactCheckRecord record = factCheckRecordMapper.getById(appeal.getRecordId());
        if (record == null) {
            throw new RecordNotFoundException(MessageConstant.RECORD_NOT_FOUND);
        }
        if (record.getStatus() == null || record.getStatus() != 2) {
            throw new BaseException("该申诉关联的记录未完成，无法采纳修改");
        }

        // 保存版本快照（来源：申诉采纳）
        conclusionRevisionMapper.insert(ConclusionRevision.builder()
                .recordId(record.getId())
                .oldVerdict(record.getVerdict())
                .newVerdict(handleDTO.getNewVerdict())
                .oldReasoningChain(record.getReasoningChain())
                .newReasoningChain(handleDTO.getNewReasoningChain())
                .reason(StringUtils.hasLength(handleDTO.getHandleResult())
                        ? handleDTO.getHandleResult() : "申诉采纳，修改结论")
                .source(2)
                .appealId(appeal.getId())
                .adminId(adminId)
                .createTime(LocalDateTime.now())
                .build());

        // 更新记录最新结论
        factCheckRecordMapper.update(FactCheckRecord.builder()
                .id(record.getId())
                .verdict(handleDTO.getNewVerdict())
                .verdictSource(2)
                .reasoningChain(handleDTO.getNewReasoningChain())
                .version(record.getVersion() == null ? 2 : record.getVersion() + 1)
                .updateTime(LocalDateTime.now())
                .build());

        // 更新申诉单
        appealMapper.update(Appeal.builder()
                .id(appeal.getId())
                .status(1)
                .handleResult(StringUtils.hasLength(handleDTO.getHandleResult())
                        ? handleDTO.getHandleResult() : "已采纳并修改结论")
                .handledBy(adminId)
                .handleTime(LocalDateTime.now())
                .build());

        // 通知用户
        messageMapper.insert(Message.builder()
                .userId(appeal.getUserId())
                .type(4)
                .title("申诉已采纳")
                .content("您的申诉已被采纳，相关检测结论已修改，请查看最新结论")
                .relatedId(appeal.getId())
                .isRead(0)
                .createTime(LocalDateTime.now())
                .build());

        // 操作日志
        operationLogMapper.insert(OperationLog.builder()
                .adminId(adminId)
                .operation("采纳申诉并修改结论")
                .method("AdminAppealServiceImpl.handleAccept")
                .params("appealId=" + appeal.getId() + ", recordId=" + record.getId())
                .createTime(LocalDateTime.now())
                .build());
        log.info("申诉采纳处理完成：appealId={}, recordId={}", appeal.getId(), record.getId());
    }

    /**
     * 驳回申诉
     */
    private void handleReject(Appeal appeal, AdminAppealHandleDTO handleDTO, Long adminId) {
        // 驳回原因列宽 VARCHAR(500)，超长必须在这里拦下（P2-17）
        ValidationUtils.requireText(handleDTO.getHandleResult(), "驳回原因", 500);

        appealMapper.update(Appeal.builder()
                .id(appeal.getId())
                .status(2)
                .handleResult(handleDTO.getHandleResult())
                .handledBy(adminId)
                .handleTime(LocalDateTime.now())
                .build());

        messageMapper.insert(Message.builder()
                .userId(appeal.getUserId())
                .type(4)
                .title("申诉已驳回")
                .content("您的申诉已被驳回，原因：" + handleDTO.getHandleResult())
                .relatedId(appeal.getId())
                .isRead(0)
                .createTime(LocalDateTime.now())
                .build());

        operationLogMapper.insert(OperationLog.builder()
                .adminId(adminId)
                .operation("驳回申诉")
                .method("AdminAppealServiceImpl.handleReject")
                .params("appealId=" + appeal.getId())
                .createTime(LocalDateTime.now())
                .build());
        log.info("申诉驳回处理完成：appealId={}", appeal.getId());
    }

    /**
     * 申诉实体转视图
     */
    private AppealVO toVO(Appeal appeal) {
        String recordContent = null;
        FactCheckRecord record = factCheckRecordMapper.getById(appeal.getRecordId());
        if (record != null) {
            recordContent = record.getContent();
        }
        return AppealVO.builder()
                .id(appeal.getId())
                .recordId(appeal.getRecordId())
                .recordContent(recordContent)
                .reasonType(appeal.getReasonType())
                .content(appeal.getContent())
                .status(appeal.getStatus())
                .handleResult(appeal.getHandleResult())
                .createTime(appeal.getCreateTime())
                .handleTime(appeal.getHandleTime())
                .build();
    }
}
