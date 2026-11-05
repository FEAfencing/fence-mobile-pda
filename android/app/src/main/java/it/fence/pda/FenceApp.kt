package it.fence.pda

import android.app.Application
import it.fence.pda.core.Api
import it.fence.pda.core.Prefs
import it.fence.pda.database.AppDb
import it.fence.pda.sync.SyncManager
import it.fence.pda.sync.Scheduler

class FenceApp : Application() {
    lateinit var prefs: Prefs
    lateinit var db: AppDb
    lateinit var api: Api
    lateinit var sync: SyncManager

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        db = AppDb.get(this)
        api = Api(prefs)
        sync = SyncManager(this, prefs, db, api)
        Scheduler.periodic(this)
    }
}
