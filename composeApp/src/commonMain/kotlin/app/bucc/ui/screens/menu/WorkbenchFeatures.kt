package app.bucc.ui.screens.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.bucc.repository.ScheduleStore
import app.bucc.ui.icons.FeatureIcon

internal data class WorkbenchFeature(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
)

internal data class WorkbenchFeatureSection(
    val title: String,
    val features: List<WorkbenchFeature>,
    val id: String = title,
)

internal object WorkbenchOrderStore {
  private const val KEY_PREFIX = "workbench_order"

  fun read(accountId: String, sectionId: String, defaults: List<String>): List<String> {
    if (accountId.isBlank()) return defaults
    val saved =
        ScheduleStore.settings
            .getStringOrNull("$KEY_PREFIX:$accountId:$sectionId")
            ?.split(',')
            ?.filter(String::isNotBlank)
            .orEmpty()
    return saved.filter { it in defaults }.distinct() + defaults.filter { it !in saved }
  }

  fun write(accountId: String, sectionId: String, featureIds: List<String>) {
    if (accountId.isNotBlank()) {
      ScheduleStore.settings.putString(
          "$KEY_PREFIX:$accountId:$sectionId",
          featureIds.joinToString(","),
      )
    }
  }
}

internal fun workbenchFeatureSections(accountId: String = ""): List<WorkbenchFeatureSection> =
    listOf(
            WorkbenchFeatureSection(
                title = "学习",
                features =
                    listOf(
                        WorkbenchFeature(
                            "schedule",
                            "课表查询",
                            "查看课程表，支持周视图和学期切换",
                            FeatureIcon.SCHEDULE.vector,
                        ),
                        WorkbenchFeature(
                            "exam",
                            "考试查询",
                            "查看考试安排，支持学期切换",
                            FeatureIcon.EXAM.vector,
                        ),
                        WorkbenchFeature(
                            "grade",
                            "成绩",
                            "查询成绩与自定义学院推免 GPA",
                            FeatureIcon.GRADE.vector,
                        ),
                        WorkbenchFeature("bykc", "博雅课程", "浏览选课，查看已选，签到签退", FeatureIcon.BYKC.vector),
                        WorkbenchFeature(
                            "attendance",
                            "签到查询",
                            "按学期、日期或课程查看出勤记录",
                            FeatureIcon.ATTENDANCE.vector,
                        ),
                        WorkbenchFeature(
                            "homework",
                            "作业中心",
                            "汇总五个来源的作业、实验和报告",
                            FeatureIcon.HOMEWORK.vector,
                        ),
                        WorkbenchFeature(
                            "evaluation",
                            "自动评教",
                            "一键完成学期末评教任务",
                            FeatureIcon.EVALUATION.vector,
                        ),
                    ),
            ),
            WorkbenchFeatureSection(
                title = "服务",
                features =
                    listOf(
                        WorkbenchFeature(
                            "classroom",
                            "空教室查询",
                            "查询各校区空闲教室",
                            FeatureIcon.CLASSROOM.vector,
                        ),
                        WorkbenchFeature(
                            "libbook",
                            "图书馆空间预约",
                            "座位地图、研讨间预约与预约记录",
                            FeatureIcon.LIBRARY.vector,
                        ),
                        WorkbenchFeature("td", "TD 线", "配置图片、查询次数与前台执行", FeatureIcon.TD.vector),
                        WorkbenchFeature(
                            "cgyy",
                            "研讨室预约",
                            "查询、提交和管理研讨室预约",
                            FeatureIcon.CGYY.vector,
                        ),
                        WorkbenchFeature(
                            "ygdk",
                            "阳光打卡",
                            "查看记录并提交体育活动打卡",
                            FeatureIcon.YGDK.vector,
                        ),
                        WorkbenchFeature(
                            "ihome",
                            "iHome",
                            "查看和跟进校园诉求、公告",
                            FeatureIcon.SUPPORT.vector,
                        ),
                        WorkbenchFeature(
                            "paper-plane",
                            "课堂纸飞机",
                            "查看任务、投掷并与同学互动",
                            FeatureIcon.PAPER_PLANE.vector,
                        ),
                        WorkbenchFeature(
                            "finance",
                            "校园财务",
                            "查询校园卡与网费余额",
                            FeatureIcon.FINANCE.vector,
                        ),
                    ),
            ),
        )
        .map { section ->
          val ids = section.features.map { it.id }
          val order = WorkbenchOrderStore.read(accountId, section.id, ids)
          section.copy(
              features = order.mapNotNull { id -> section.features.firstOrNull { it.id == id } }
          )
        }

@Composable
internal fun WorkbenchFeatureCard(
    feature: WorkbenchFeature,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Card(
      modifier =
          modifier
              .fillMaxWidth()
              .alpha(if (feature.enabled) 1f else .38f)
              .heightIn(min = 112.dp)
              .clickable(enabled = feature.enabled, onClick = onClick),
      colors =
          CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant,
              contentColor =
                  if (feature.enabled) MaterialTheme.colorScheme.onSurface
                  else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
          ),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
      Icon(
          imageVector = feature.icon,
          contentDescription = null,
          modifier = Modifier.size(28.dp),
          tint =
              if (feature.enabled) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(10.dp))
      Text(
          text = feature.title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
          text = feature.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
      )
    }
  }
}
