/**
 * 定时任务规则数据访问对象（DAO）
 *
 * 文件位置：legado/app/src/main/java/io/legado/app/data/dao/AutoTaskRuleDao.kt
 * 作用：提供对 auto_task_rules 表的 CRUD 操作接口，Room 框架会自动生成实现代码。
 *
 * 主要操作：
 * - all(): 获取所有任务规则
 * - getById(): 根据 ID 获取单个任务规则
 * - insert(): 插入或替换任务规则
 * - update(): 更新任务规则
 * - delete(): 删除任务规则
 */
package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.model.AutoTaskRule

@Dao
interface AutoTaskRuleDao {

    /**
     * 获取所有定时任务规则
     * @return 任务规则列表
     */
    @Query("select * from auto_task_rules")
    fun all(): List<AutoTaskRule>

    /**
     * 根据 ID 获取单个任务规则
     * @param id 任务 ID
     * @return 任务规则，不存在则返回 null
     */
    @Query("select * from auto_task_rules where id = :id")
    fun getById(id: String): AutoTaskRule?

    /**
     * 插入任务规则
     * 使用 REPLACE 策略：如果 ID 已存在则替换
     * @param rule 要插入的任务规则（可变参数，支持批量插入）
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg rule: AutoTaskRule)

    /**
     * 更新任务规则
     * 根据 主键 id 匹配现有记录进行更新
     * @param rule 要更新的任务规则（可变参数，支持批量更新）
     */
    @Update
    fun update(vararg rule: AutoTaskRule)

    /**
     * 根据 ID 删除任务规则
     * @param id 要删除的任务 ID
     */
    @Query("delete from auto_task_rules where id = :id")
    fun delete(id: String)

    /**
     * 删除所有任务规则
     */
    @Query("delete from auto_task_rules")
    fun deleteAll()
}