package app.sososafe.mobile.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName="applications") data class AppEntity(@PrimaryKey val packageName:String,val risk:Int,val updated:Long,val json:String)
@Entity(tableName="application_chunks",primaryKeys=["packageName","sequence"]) data class AppChunkEntity(val packageName:String,val sequence:Int,val json:String)
@Entity(tableName="scan_records") data class ScanEntity(@PrimaryKey val id:String,val time:Long,val json:String)
@Entity(tableName="permissions",primaryKeys=["packageName","name"]) data class PermissionEntity(val packageName:String,val name:String,val json:String)
@Entity(tableName="endpoints",primaryKeys=["packageName","value"]) data class EndpointEntity(val packageName:String,val value:String,val json:String)
@Entity(tableName="findings",primaryKeys=["packageName","id"]) data class FindingEntity(val packageName:String,val id:String,val json:String)
@Entity(tableName="connections") data class ConnectionEntity(@PrimaryKey val id:String,val time:Long,val json:String)
@Entity(tableName="actions") data class ActionEntity(@PrimaryKey val id:String,val time:Long,val json:String)

@Dao interface AuditDao {
    @Query("SELECT * FROM applications ORDER BY risk DESC") fun apps():Flow<List<AppEntity>>
    @Query("SELECT * FROM applications ORDER BY risk DESC") suspend fun allApps():List<AppEntity>
    @Query("SELECT * FROM application_chunks WHERE packageName=:pkg ORDER BY sequence") suspend fun chunks(pkg:String):List<AppChunkEntity>
    @Query("SELECT * FROM scan_records ORDER BY time DESC") fun scans():Flow<List<ScanEntity>>
    @Query("SELECT * FROM scan_records ORDER BY time DESC") suspend fun allScans():List<ScanEntity>
    @Query("SELECT * FROM connections ORDER BY time DESC LIMIT 2000") fun connections():Flow<List<ConnectionEntity>>
    @Query("SELECT * FROM connections ORDER BY time DESC") suspend fun allConnections():List<ConnectionEntity>
    @Query("SELECT * FROM actions ORDER BY time DESC") fun actions():Flow<List<ActionEntity>>
    @Query("SELECT * FROM actions ORDER BY time DESC") suspend fun allActions():List<ActionEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun app(app:AppEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun chunks(rows:List<AppChunkEntity>)
    @Query("DELETE FROM application_chunks WHERE packageName=:pkg") suspend fun clearChunks(pkg:String)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun scan(scan:ScanEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun permissions(rows:List<PermissionEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun endpoints(rows:List<EndpointEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun findings(rows:List<FindingEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun connections(rows:List<ConnectionEntity>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun action(row:ActionEntity)
    @Query("DELETE FROM permissions WHERE packageName=:pkg") suspend fun clearPermissions(pkg:String)
    @Query("DELETE FROM endpoints WHERE packageName=:pkg") suspend fun clearEndpoints(pkg:String)
    @Query("DELETE FROM findings WHERE packageName=:pkg") suspend fun clearFindings(pkg:String)
    @Query("DELETE FROM connections WHERE time<:before") suspend fun pruneConnections(before:Long)
    @Query("DELETE FROM connections WHERE id NOT IN (SELECT id FROM connections ORDER BY time DESC LIMIT 20000)") suspend fun capConnections()
    @Query("DELETE FROM applications") suspend fun clearApps()
    @Query("DELETE FROM application_chunks") suspend fun clearChunks()
    @Query("DELETE FROM permissions") suspend fun clearPermissions()
    @Query("DELETE FROM endpoints") suspend fun clearEndpoints()
    @Query("DELETE FROM findings") suspend fun clearFindings()
    @Query("DELETE FROM scan_records") suspend fun clearScans()
    @Query("DELETE FROM connections") suspend fun clearConnections()
}
@Database(entities=[AppEntity::class,AppChunkEntity::class,ScanEntity::class,PermissionEntity::class,EndpointEntity::class,FindingEntity::class,ConnectionEntity::class,ActionEntity::class],version=2,exportSchema=true)
abstract class AuditDatabase:RoomDatabase() {
    abstract fun dao():AuditDao
    companion object {
        val MIGRATION_1_2=object:Migration(1,2){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS application_chunks (packageName TEXT NOT NULL, sequence INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(packageName, sequence))")
            db.execSQL("""WITH RECURSIVE chunks(pkg,seq,part,rest) AS (
                SELECT packageName,0,substr(json,1,32768),substr(json,32769) FROM applications
                UNION ALL SELECT pkg,seq+1,substr(rest,1,32768),substr(rest,32769) FROM chunks WHERE length(rest)>0
                ) INSERT INTO application_chunks(packageName,sequence,json) SELECT pkg,seq,part FROM chunks""")
            db.execSQL("UPDATE applications SET json='{}'")
        }}
    }
}
