package tk.wasdennnoch.scoop.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import com.afollestad.inquiry.Inquiry
import com.afollestad.materialcab.MaterialCab
import tk.wasdennnoch.scoop.R
import tk.wasdennnoch.scoop.ScoopApplication
import tk.wasdennnoch.scoop.ScoopApplication.Companion.serviceActive
import tk.wasdennnoch.scoop.data.crash.Crash
import tk.wasdennnoch.scoop.data.crash.CrashAdapter
import tk.wasdennnoch.scoop.data.crash.CrashLoader
import tk.wasdennnoch.scoop.databinding.ActivityMainBinding
import tk.wasdennnoch.scoop.ui.helpers.ToolbarElevationHelper
import tk.wasdennnoch.scoop.util.AnimationUtils
import java.util.*

class MainActivity : AppCompatActivity(), CrashAdapter.Listener, SearchView.OnQueryTextListener,
    SearchView.OnCloseListener, MaterialCab.Callback {
    private val mLoader = CrashLoader()
    private var binding: ActivityMainBinding? = null
    private var mCombineApps = false
    private var mHasCrash = false
    private var mPrefs: SharedPreferences? = null
    private var mHandler: Handler? = null
    private var mAdapter: CrashAdapter? = null
    private var mNoItems: View? = null
    private var mCab: MaterialCab? = null

    // Required to properly animate the cab (otherwise it instantly hides when pressing the up button)
    private var mAnimatingCab = false
    private var mDestroyed = false
    private var mCheckPending = true
    private var mIsAvailable = true
    private var mWasLoading = false
    private val mUpdateCheckerRunnable: Runnable = object : Runnable {
        override fun run() {
            if (sUpdateRequired) {
                sUpdateRequired = false
                if (mCombineApps) // It doesn't look right when there's suddenly a single crash of
                // a different app in the list
                    return
                if (sVisible && sNewCrash != null) {
                    mAdapter!!.addCrash(sNewCrash)
                    updateViewStates(false)
                    sNewCrash = null
                } else {
                    loadData()
                }
            }
            mHandler!!.postDelayed(this, UPDATE_DELAY.toLong())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (mPrefs!!.getBoolean("force_english", false)) {
            // TODO: Use ConfigurationCompat
            val config = resources.configuration
            config.locale = Locale.ENGLISH
            resources.updateConfiguration(config, null)
        }

        // To make vector drawables work as menu item drawables
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding!!.root)
        setSupportActionBar(binding!!.mainToolbar.toolbar)

        mAdapter = CrashAdapter(this, this)
        binding!!.mainCrashView.adapter = mAdapter
        binding!!.mainCrashView.isGone = true
        ToolbarElevationHelper(binding!!.mainCrashView, binding!!.mainToolbar.toolbar)

        val i = intent
        mHasCrash = i.hasExtra(EXTRA_CRASH)
        if (mHasCrash) {
            val c: Crash? = i.getParcelableExtra(EXTRA_CRASH)
            val crashes = ArrayList<Crash?>()
            crashes.add(c)
            if (c?.children != null) {
                crashes.addAll(c.children)
            }
            mAdapter!!.setCrashes(crashes)
            supportActionBar?.title =
                CrashLoader.getAppName(this, c?.packageName, true)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }

        mCombineApps = mPrefs!!.getBoolean("combine_same_apps", false)
        mAdapter!!.setCombineSameApps(!mHasCrash && mCombineApps)
        binding!!.mainCrashView.setReverseOrder(mHasCrash || !mCombineApps)

        Inquiry.newInstance(this, "crashes")
            .instanceName("main")
            .build()

        if (savedInstanceState == null) {
            sUpdateRequired = false
            mCab = MaterialCab(this, R.id.main_cab_stub)
            if (!mHasCrash) {
                loadData()
            } else {
                updateViewStates(false)
            }
        } else {
            mAdapter!!.restoreInstanceState(savedInstanceState)
            mCab = MaterialCab.restoreState(savedInstanceState, this, this)
            updateViewStates(false)
        }
        mHandler = Handler()

        AvailabilityCheck().execute()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mAdapter!!.saveInstanceState(outState)
        mCab!!.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        // Cheap way to instantly apply changes
        mAdapter!!.setSearchPackageName(
            this, mPrefs!!.getBoolean("search_package_name", true)
        )
        sVisible = true
        mHandler!!.post(mUpdateCheckerRunnable)
    }

    public override fun onPause() {
        super.onPause()
        sVisible = false
        mHandler!!.removeCallbacks(mUpdateCheckerRunnable)
        if (isFinishing && !mHasCrash) {
            Inquiry.destroy("main")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mDestroyed = true
    }

    override fun onBackPressed() {
        if (mCab!!.isActive) {
            mAdapter!!.setSelectionEnabled(false)
        } else {
            super.onBackPressed()
        }
    }

    private fun updateViewStates(loading: Boolean?) {
        var newLoading = loading
        if (newLoading == null) {
            newLoading = mWasLoading
        }
        mWasLoading = newLoading
        if (mCheckPending) {
            newLoading = true
        }
        val empty = mAdapter!!.isEmpty
        binding!!.mainProgressbar.isVisible = newLoading
        binding!!.mainCrashView.isGone = newLoading || empty || !mIsAvailable

        if (!newLoading && empty && mIsAvailable) {
            if (mNoItems == null) {
                mNoItems = binding!!.mainNoItemsStub.inflate()
            }
            mNoItems!!.isVisible = true
        } else if (mNoItems != null) {
            mNoItems!!.isGone = true
        }
        if (!mIsAvailable) {
            if (mNoItems == null) {
                mNoItems = binding!!.mainNoPermissionStub.inflate()
            }
        }
    }

    private fun loadData() {
        mAdapter!!.setSelectionEnabled(false)
        updateViewStates(true)
        mLoader.loadData(
            this,
            mPrefs!!.getBoolean("combine_same_stack_trace", true),
            mPrefs!!.getBoolean("combine_same_apps", false),
            mutableListOf(
                *mPrefs
                    ?.getString("blacklisted_packages", "")
                    ?.split(",".toRegex())!!.toTypedArray()
            )
        )
    }

    fun onDataLoaded(data: ArrayList<Crash?>?) {
        mAdapter!!.setCrashes(data)
        updateViewStates(false)
    }

    private fun setCabActive(active: Boolean) {
        if (active) {
            mCab!!.start(this)
            mCab!!.toolbar.outlineProvider = null
            AnimationUtils.slideToolbar(
                mCab!!.toolbar, false, AnimationUtils.ANIM_DURATION_DEFAULT
            )
        } else {
            mAnimatingCab = true
            AnimationUtils.slideToolbar(
                mCab!!.toolbar, true, AnimationUtils.ANIM_DURATION_DEFAULT, false
            ) {
                mAnimatingCab = false
                mCab!!.finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CODE_CHILDREN_VIEW && resultCode == RESULT_OK) {
            loadData()
        }
    }

    override fun onCrashClicked(crash: Crash) {
        if (mCombineApps && !mHasCrash) {
            startActivityForResult(
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_CRASH, crash), CODE_CHILDREN_VIEW
            )
        } else {
            startActivity(
                Intent(this, DetailActivity::class.java)
                    .putExtra(DetailActivity.EXTRA_CRASH, crash)
            )
        }
    }

    override fun onToggleSelectionMode(enabled: Boolean) {
        setCabActive(enabled)
    }

    override fun onItemSelected(count: Int) {
        mCab!!.setTitle(
            String.format(
                resources.getQuantityString(
                    R.plurals.items_selected_count,
                    count
                ), count
            )
        )
    }

    override fun onCabCreated(cab: MaterialCab, menu: Menu): Boolean {
        return true
    }

    override fun onCabItemClicked(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_cab_delete) {
            val items = mAdapter!!.selectedItems
            if (items.isEmpty()) {
                return true
            }
            val content = String.format(
                resources.getQuantityString(R.plurals.delete_multiple_confirm, items.size),
                items.size
            )
            AlertDialog.Builder(this)
                .setMessage(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    // TODO THIS IS A MESS
                    val instance = Inquiry.get("main")
                    for (c in items) {
                        if (!mHasCrash && c.children != null) {
                            for (cc in c.children) {
                                if (cc.hiddenIds != null) {
                                    instance.delete(Crash::class.java)
                                        .whereIn("_id", *cc.hiddenIds.toTypedArray())
                                        .run()
                                }
                                mAdapter!!.removeCrash(cc)
                            }
                            instance.delete(Crash::class.java)
                                .values(c.children)
                                .run()
                        }
                        if (c.hiddenIds != null) {
                            instance.delete(Crash::class.java)
                                .whereIn("_id", *c.hiddenIds.toTypedArray())
                                .run()
                        }
                        instance.delete(Crash::class.java)
                            .values(listOf(c))
                            .run()
                        mAdapter!!.removeCrash(c)
                    }
                    mAdapter!!.setSelectionEnabled(false)
                    setResult(RESULT_OK) // Reload overview when going back to reflect changes
                    if (mHasCrash && mAdapter!!.isEmpty) {
                        finish() // Everything deleted, go back to overview
                    } else {
                        updateViewStates(false)
                    }
                }
                .show()
            return true
        }
        return true
    }

    override fun onCabFinished(cab: MaterialCab): Boolean {
        mAdapter!!.setSelectionEnabled(false)
        return !mAnimatingCab
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchItem = menu.findItem(R.id.menu_main_search)
        val searchView = searchItem.actionView as SearchView
        searchView.setOnQueryTextListener(this)
        searchView.setOnCloseListener(this)
        return true
    }

    override fun onClose(): Boolean {
        mAdapter!!.search(this, null)
        return false
    }

    override fun onQueryTextChange(newText: String): Boolean {
        mAdapter!!.search(this, newText)
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_main_clear -> {
                AlertDialog.Builder(this)
                    .setMessage(R.string.dialog_clear_content)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        Inquiry.get("main")
                            .dropTable(Crash::class.java) // bam!
                        onDataLoaded(null)
                    }
                    .show()
                return true
            }
            R.id.menu_main_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    internal inner class AvailabilityCheck : AsyncTask<Void?, Void?, Boolean>() {
        override fun doInBackground(vararg params: Void?): Boolean {
            val app = application as ScoopApplication
            return serviceActive() || app.startService()
        }

        override fun onPostExecute(available: Boolean) {
            if (!mDestroyed) {
                mCheckPending = false
                mIsAvailable = available
                updateViewStates(null)
            }
        }
    }

    companion object {
        private const val EXTRA_CRASH = "tk.wasdennnoch.scoop.EXTRA_CRASH"
        private const val CODE_CHILDREN_VIEW = 1
        private const val UPDATE_DELAY = 200
        private var sUpdateRequired = false
        private var sVisible = false
        private var sNewCrash: Crash? = null

        @JvmStatic
        fun requestUpdate(newCrash: Crash?) {
            sUpdateRequired = true
            if (sVisible) {
                sNewCrash = newCrash
            }
        }
    }
}
