package ykd.ykd.memory.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;
import ykd.ykd.memory.model.ReminderTaskEntity;

import java.util.List;

@Mapper
public interface ReminderTaskMapper {

    @Insert("INSERT INTO reminder_task (task_id, user_id, message, time_expression, task_type, interval_seconds, daily_time, delay_seconds, needs_processing) VALUES (#{taskId}, #{userId}, #{message}, #{timeExpression}, #{taskType}, #{intervalSeconds}, #{dailyTime}, #{delaySeconds}, #{needsProcessing})")
    @SelectKey(statement = "SELECT last_insert_rowid()", keyProperty = "id", before = false, resultType = Long.class)
    int insert(ReminderTaskEntity entity);

    @Select("SELECT * FROM reminder_task WHERE status = 'ACTIVE'")
    List<ReminderTaskEntity> findAllActive();

    @Update("UPDATE reminder_task SET status = 'CANCELLED' WHERE task_id = #{taskId}")
    int cancelByTaskId(String taskId);
}
