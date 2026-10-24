package app.bucc.api

import kotlinx.coroutines.sync.Mutex

internal val localConnectionTestMutex = Mutex()
