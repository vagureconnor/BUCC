package app.bucc.ui.screens.classroom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bucc.api.feature.ClassroomApi
import app.bucc.model.dto.ClassroomInfo
import app.bucc.model.dto.ClassroomQueryResponse
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** 教室查询 UI 状态密封类。 */
sealed class ClassroomUiState {
  object Idle : ClassroomUiState()

  object Loading : ClassroomUiState()

  data class Success(val data: ClassroomQueryResponse) : ClassroomUiState()

  data class Error(val message: String) : ClassroomUiState()
}

/** 教室查询模块的 ViewModel。 负责校区选择、日期选择以及在结果中进行模糊搜索过滤。 */
class ClassroomViewModel(private val api: ClassroomApi = ClassroomApi()) : ViewModel() {
  private val _uiState = MutableStateFlow<ClassroomUiState>(ClassroomUiState.Idle)
  /** 核心查询状态流。 */
  val uiState: StateFlow<ClassroomUiState> = _uiState.asStateFlow()

  private val _xqid = MutableStateFlow(1) // 1: 学院路, 2: 沙河, 3: 杭州
  /** 当前选中的校区 ID。 */
  val xqid: StateFlow<Int> = _xqid.asStateFlow()

  private val _date = MutableStateFlow(getCurrentDate())
  /** 当前选中的查询日期。 */
  val date: StateFlow<String> = _date.asStateFlow()

  private val _searchQuery = MutableStateFlow("")
  /** 当前的搜索关键字流。 */
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  private val _selectedBuilding = MutableStateFlow<String?>(null)
  /** 当前选中的楼栋。null 表示展示全部楼栋。 */
  val selectedBuilding: StateFlow<String?> = _selectedBuilding.asStateFlow()

  private val _selectedFloor = MutableStateFlow<ClassroomFloor?>(null)
  /** 当前选中的楼层；null 表示全部楼层。 */
  val selectedFloor: StateFlow<ClassroomFloor?> = _selectedFloor.asStateFlow()

  private val _sortOrder = MutableStateFlow(ClassroomSortOrder.FREE_PERIODS)
  /** 教室结果排序方式。 */
  val sortOrder: StateFlow<ClassroomSortOrder> = _sortOrder.asStateFlow()

  /** 当前查询结果中的可选楼栋列表。 */
  val availableBuildings: StateFlow<List<String>> =
      _uiState
          .map { state ->
            if (state is ClassroomUiState.Success) {
              sortBuildings(state.data.d.list)
            } else {
              emptyList()
            }
          }
          .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  /** 当前楼栋筛选范围内可选的楼层。 */
  val availableFloors: StateFlow<List<ClassroomFloor>> =
      combine(_uiState, _selectedBuilding) { state, selectedBuilding ->
            if (state !is ClassroomUiState.Success) return@combine emptyList()
            state.data.d.list
                .filterKeys { selectedBuilding == null || it == selectedBuilding }
                .values
                .flatten()
                .map { classroomFloorOption(it.name) }
                .distinct()
                .sortedWith(
                    compareBy<ClassroomFloor> { it.number == null }.thenBy { it.number ?: 0 }
                )
          }
          .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  /** 根据楼栋与搜索关键字过滤后的教室数据流。 */
  val filteredData: StateFlow<Map<String, List<ClassroomInfo>>> =
      combine(_uiState, _selectedBuilding, _selectedFloor, _sortOrder, _searchQuery) {
              state,
              selectedBuilding,
              selectedFloor,
              sortOrder,
              query ->
            if (state is ClassroomUiState.Success) {
              val allData = state.data.d.list
              val buildingFilteredData =
                  selectedBuilding?.let { building ->
                    allData[building]?.let { listOfClassroom -> mapOf(building to listOfClassroom) }
                        ?: emptyMap()
                  } ?: allData
              buildingFilteredData
                  .mapValues { (building, list) ->
                    list
                        .asSequence()
                        .filter {
                          selectedFloor == null || classroomFloorOption(it.name) == selectedFloor
                        }
                        .filter {
                          query.isBlank() ||
                              building.contains(query, true) ||
                              it.name.contains(query, true)
                        }
                        .sortedWith(classroomComparator(sortOrder))
                        .toList()
                  }
                  .filter { (building, list) ->
                    (query.isBlank() || building.contains(query, true) || list.isNotEmpty()) &&
                        list.isNotEmpty()
                  }
            } else emptyMap()
          }
          .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

  /** 更新搜索关键字。 */
  fun setSearchQuery(query: String) {
    _searchQuery.value = query
  }

  /** 切换楼栋筛选；再次点击当前楼栋会恢复到全部楼栋。 */
  fun toggleBuilding(building: String) {
    _selectedBuilding.value = if (_selectedBuilding.value == building) null else building
    _selectedFloor.value = null
  }

  /** 选择楼栋；null 表示全部楼栋。 */
  fun setBuilding(building: String?) {
    _selectedBuilding.value = building
    _selectedFloor.value = null
  }

  /** 选择楼层；null 表示全部楼层。 */
  fun setFloor(floor: ClassroomFloor?) {
    _selectedFloor.value = floor
  }

  fun setSortOrder(order: ClassroomSortOrder) {
    _sortOrder.value = order
  }

  /** 切换校区并自动重新查询。 */
  fun setXqid(id: Int) {
    _xqid.value = id
    _selectedBuilding.value = null
    _selectedFloor.value = null
    query()
  }

  /** 切换日期并自动重新查询。 */
  fun setDate(date: String) {
    _date.value = date
    _selectedBuilding.value = null
    _selectedFloor.value = null
    query()
  }

  /** 执行查询动作。 */
  fun query() {
    viewModelScope.launch {
      _uiState.value = ClassroomUiState.Loading
      api.queryClassrooms(_xqid.value, _date.value)
          .onSuccess {
            _uiState.value = ClassroomUiState.Success(it)
            if (_selectedBuilding.value !in it.d.list.keys) {
              _selectedBuilding.value = null
            }
            val floors =
                it.d.list
                    .filterKeys { building ->
                      _selectedBuilding.value == null || building == _selectedBuilding.value
                    }
                    .values
                    .flatten()
                    .map { classroomFloorOption(it.name) }
                    .toSet()
            if (_selectedFloor.value !in floors) {
              _selectedFloor.value = null
            }
          }
          .onFailure { _uiState.value = ClassroomUiState.Error(it.message ?: "未知错误") }
    }
  }

  @OptIn(ExperimentalTime::class)
  private fun getCurrentDate(): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return "${now.year}-${(now.month.ordinal + 1).toString().padStart(2, '0')}-${now.day.toString().padStart(2, '0')}"
  }
}

enum class ClassroomSortOrder(val label: String) {
  FREE_PERIODS("空闲时段最多"),
  NAME("名称"),
}

data class ClassroomFloor(val number: Int?) {
  val label: String
    get() = number?.let { "${it}层" } ?: "未知楼层"
}

/** 从教室名中的房间号读取楼层；三位房间号取首位，四位及以上取前两位。 */
internal fun classroomFloor(classroomName: String): Int? {
  val roomNumber =
      Regex("(?:^|[^0-9])([0-9]{3,})(?:$|[^0-9])").find(classroomName)?.groupValues?.get(1)
          ?: return null
  val floorDigits = roomNumber.take(1)
  return floorDigits.toIntOrNull()
}

internal fun classroomFloorOption(classroomName: String): ClassroomFloor =
    ClassroomFloor(classroomFloor(classroomName))

private fun classroomComparator(order: ClassroomSortOrder): Comparator<ClassroomInfo> =
    when (order) {
      ClassroomSortOrder.FREE_PERIODS ->
          compareByDescending<ClassroomInfo> { freePeriodCount(it.kxsds) }
              .thenComparator { left, right -> naturalCompare(left.name, right.name) }
      ClassroomSortOrder.NAME -> Comparator { left, right -> naturalCompare(left.name, right.name) }
    }

private fun freePeriodCount(periods: String): Int =
    periods.split(",").mapNotNull { it.trim().toIntOrNull() }.distinct().size

internal fun sortBuildings(allData: Map<String, List<ClassroomInfo>>): List<String> {
  val analysis = analyzeBuildingFloorIds(allData)
  return if (analysis.canUseFloorIdOrdering) {
    analysis.entries
        .sortedWith(
            compareBy<BuildingFloorAnalysisEntry> { it.floorId }
                .thenComparator { left, right -> naturalCompare(left.name, right.name) }
        )
        .map { it.name }
  } else {
    allData.keys.sortedWith(::naturalCompare)
  }
}

internal fun analyzeBuildingFloorIds(
    allData: Map<String, List<ClassroomInfo>>
): BuildingFloorAnalysis {
  val entries =
      allData.map { (building, classrooms) ->
        val floorIds = classrooms.map { it.floorid.trim() }.filter { it.isNotEmpty() }.toSet()
        BuildingFloorAnalysisEntry(
            name = building,
            floorIds = floorIds,
            floorId = floorIds.singleOrNull(),
        )
      }

  val canUseFloorIdOrdering =
      entries.isNotEmpty() &&
          entries.all { it.floorIds.size == 1 && it.floorId != null } &&
          entries.mapNotNull { it.floorId }.distinct().size == entries.size

  return BuildingFloorAnalysis(entries = entries, canUseFloorIdOrdering = canUseFloorIdOrdering)
}

internal data class BuildingFloorAnalysis(
    val entries: List<BuildingFloorAnalysisEntry>,
    val canUseFloorIdOrdering: Boolean,
)

internal data class BuildingFloorAnalysisEntry(
    val name: String,
    val floorIds: Set<String>,
    val floorId: String?,
)

internal fun naturalCompare(left: String, right: String): Int {
  val leftParts = splitNaturalParts(left)
  val rightParts = splitNaturalParts(right)
  val maxSize = maxOf(leftParts.size, rightParts.size)
  for (index in 0 until maxSize) {
    val leftPart = leftParts.getOrNull(index) ?: return -1
    val rightPart = rightParts.getOrNull(index) ?: return 1
    val result =
        when {
          leftPart is NaturalPart.Number && rightPart is NaturalPart.Number ->
              leftPart.value.compareTo(rightPart.value)
          leftPart is NaturalPart.Text && rightPart is NaturalPart.Text ->
              leftPart.value.compareTo(rightPart.value)
          leftPart is NaturalPart.Number -> -1
          else -> 1
        }
    if (result != 0) return result
  }
  return 0
}

internal fun splitNaturalParts(value: String): List<NaturalPart> {
  if (value.isEmpty()) return emptyList()

  val parts = mutableListOf<NaturalPart>()
  val buffer = StringBuilder()
  var digitMode = value.first().isDigit()

  fun flush() {
    if (buffer.isEmpty()) return
    val text = buffer.toString()
    parts +=
        if (digitMode) {
          NaturalPart.Number(text.toIntOrNull() ?: Int.MAX_VALUE)
        } else {
          NaturalPart.Text(text)
        }
    buffer.clear()
  }

  value.forEach { char ->
    val isDigit = char.isDigit()
    if (buffer.isNotEmpty() && isDigit != digitMode) {
      flush()
      digitMode = isDigit
    } else if (buffer.isEmpty()) {
      digitMode = isDigit
    }
    buffer.append(char)
  }
  flush()

  return parts
}

internal sealed interface NaturalPart {
  data class Number(val value: Int) : NaturalPart

  data class Text(val value: String) : NaturalPart
}
