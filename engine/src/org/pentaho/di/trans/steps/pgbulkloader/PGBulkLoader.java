/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2013 by Pentaho : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.di.trans.steps.pgbulkloader;

//
// The "designer" notes of the PostgreSQL bulkloader:
// ----------------------------------------------
//
// Let's see how fast we can push data down the tube with the use of COPY FROM STDIN
//
//

import java.math.BigDecimal;

import org.apache.commons.vfs2.FileObject;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleDatabaseException;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleFileException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.util.StreamLogger;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.postgresql.copy.PGCopyOutputStream;
import org.postgresql.core.BaseConnection;
import org.postgresql.PGConnection;

/**
 * Performs a bulk load to a postgres table.
 *
 * Based on (copied from) Sven Boden's Oracle Bulk Loader step
 *
 * @author matt
 * @since 28-mar-2008
 */
public class PGBulkLoader extends BaseStep implements StepInterface {
  private static Class<?> PKG = PGBulkLoaderMeta.class; // for i18n purposes, needed by Translator2!!

  private PGBulkLoaderMeta meta;
  private PGBulkLoaderData data;
  private PGCopyOutputStream pgCopyOut;

  public PGBulkLoader( StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta,
    Trans trans ) {
    super( stepMeta, stepDataInterface, copyNr, transMeta, trans );
  }

  /**
   * Get the contents of the control file as specified in the meta object
   *
   * @param meta
   *          the meta object to model the control file after
   *
   * @return a string containing the control file contents
   */
  public String getCopyCommand( ) throws KettleException {
    DatabaseMeta dm = meta.getDatabaseMeta();


    StringBuilder contents = new StringBuilder( 500 );

    String tableName =
      dm.getQuotedSchemaTableCombination(
        environmentSubstitute( meta.getSchemaName() ), environmentSubstitute( meta.getTableName() ) );

    // Set the date style...
    //
    // contents.append("SET DATESTYLE ISO;"); // This is the default but we set it anyway...
    // contents.append(Const.CR);

    // Create a Postgres / Greenplum COPY string for use with a psql client
    contents.append( "COPY " );
    // Table name

    contents.append( tableName );

    // Names of columns

    contents.append( " ( " );

    String[] streamFields = meta.getFieldStream();
    String[] tableFields = meta.getFieldTable();

    if ( streamFields == null || streamFields.length == 0 ) {
      throw new KettleException( "No fields defined to load to database" );
    }

    for ( int i = 0; i < streamFields.length; i++ ) {
      if ( i != 0 ) {
        contents.append( ", " );
      }
      contents.append( dm.quoteField( tableFields[i] ) );
    }

    contents.append( " ) " );

    // The "FROM" filename
    contents.append( " FROM STDIN" ); // FIFO file

    // The "FORMAT" clause
    contents.append( " WITH CSV DELIMITER AS '" ).append( environmentSubstitute( meta.getDelimiter() ) )
        .append( "' QUOTE AS '" ).append(
      environmentSubstitute( meta.getEnclosure() ) ).append( "'" );
    contents.append( ";" ).append( Const.CR );

    return contents.toString();
  }

  private void do_copy ( PGBulkLoaderMeta meta, boolean wait ) throws KettleException {
    Runtime rt = Runtime.getRuntime();

    // Retrieve bits of meta information we will need
    DatabaseMeta dm = meta.getDatabaseMeta();
    String tableName =
       dm.getQuotedSchemaTableCombination(
       environmentSubstitute( meta.getSchemaName() ), environmentSubstitute( meta.getTableName() ) );

    String copyCmd = null;
    try {
       Connection connection = data.db.getConnection();

        copyCmd = getCopyCommand( );
        logBasic( "Launching command: " + copyCmd );
        pgCopyOut = new PGCopyOutputStream((PGConnection) connection, copyCmd);

    } catch ( Exception ex ) {
      throw new KettleException( "Error while preparing the COPY " + copyCmd, ex );
    }
  }

  public boolean processRow( StepMetaInterface smi, StepDataInterface sdi ) throws KettleException {
    meta = (PGBulkLoaderMeta) smi;
    data = (PGBulkLoaderData) sdi;

    try {
      Object[] r = getRow(); // Get row from input rowset & set row busy!

      if ( r == null ) { // no more input to be expected...

        setOutputDone();

        // Close the output stream...
        // will be null if no records (empty stream)
        if ( data != null ) {
          pgCopyOut.flush();
          pgCopyOut.endCopy();

        } 
        // Commit
        data.db.commit();
        return false;
      }

      if ( first ) {
        first = false;

        // Cache field indexes.
        //
        data.keynrs = new int[meta.getFieldStream().length];
        for ( int i = 0; i < data.keynrs.length; i++ ) {
          data.keynrs[i] = getInputRowMeta().indexOfValue( meta.getFieldStream()[i] );
        }

        // execute the copy statement... pgCopyOut is setup there
        //
        do_copy ( meta, true );


        // Write rows of data hereafter...
        //
      }

      writeRowToPostgres( getInputRowMeta(), r );

      putRow( getInputRowMeta(), r );
      incrementLinesOutput();

      return true;
    } catch ( Exception e ) {
      logError( BaseMessages.getString( PKG, "GPBulkLoader.Log.ErrorInStep" ), e );
      setErrors( 1 );
      stopAll();
      setOutputDone(); // signal end to receiver(s)
      return false;
    }
  }

  private void writeRowToPostgres( RowMetaInterface rowMeta, Object[] r ) throws KettleException {

    try {
      // So, we have this output stream to which we can write CSV data to.
      // Basically, what we need to do is write the binary data (from strings to it as part of this proof of concept)
      //
      // Let's assume the data is in the correct format here too.
      //
      for ( int i = 0; i < data.keynrs.length; i++ ) {
        if ( i > 0 ) {
          // Write a separator
          //
          pgCopyOut.write( data.separator );
        }

        int index = data.keynrs[i];
        ValueMetaInterface valueMeta = rowMeta.getValueMeta( index );
        Object valueData = r[index];

        if ( valueData != null ) {
          switch ( valueMeta.getType() ) {
            case ValueMetaInterface.TYPE_STRING:
              pgCopyOut.write( data.quote );

              // No longer dump the bytes for a Lazy Conversion;
              // We need to escape the quote characters in every string
              String quoteStr = new String( data.quote );
              String escapedString = valueMeta.getString( valueData ).replace( quoteStr, quoteStr + quoteStr );
              pgCopyOut.write( escapedString.getBytes() );

              pgCopyOut.write( data.quote);
              break;
            case ValueMetaInterface.TYPE_INTEGER:
              if ( valueMeta.isStorageBinaryString() ) {
                pgCopyOut.write( (byte[]) valueData);
              } else {
                pgCopyOut.write( Long.toString( valueMeta.getInteger( valueData ) ).getBytes() );
              }
              break;
            case ValueMetaInterface.TYPE_DATE:
              // Format the date in the right format.
              //
              switch ( data.dateFormatChoices[i] ) {
              // Pass the data along in the format chosen by the user OR in binary format...
              //
                case PGBulkLoaderMeta.NR_DATE_MASK_PASS_THROUGH:
                  if ( valueMeta.isStorageBinaryString() ) {
                    pgCopyOut.write( (byte[]) valueData);
                  } else {
                    String dateString = valueMeta.getString( valueData );
                    if ( dateString != null ) {
                      pgCopyOut.write( dateString.getBytes());
                    }
                  }
                  break;

                // Convert to a "YYYY-MM-DD" format
                //
                case PGBulkLoaderMeta.NR_DATE_MASK_DATE:
                  String dateString = data.dateMeta.getString( valueMeta.getDate( valueData ) );
                  if ( dateString != null ) {
                    pgCopyOut.write( dateString.getBytes() );
                  }
                  break;

                // Convert to a "YYYY-MM-DD HH:MM:SS.mmm" format
                //
                case PGBulkLoaderMeta.NR_DATE_MASK_DATETIME:
                  String dateTimeString = data.dateTimeMeta.getString( valueMeta.getDate( valueData ) );
                  if ( dateTimeString != null ) {
                    pgCopyOut.write( dateTimeString.getBytes() );
                  }
                  break;

                default:
                  throw new KettleException ( "PGBulkLoader doesn't know how to handle date (neither passthrough, nor date or datetime for field " + valueMeta.getName() );
              }
              break;
            case ValueMetaInterface.TYPE_TIMESTAMP:
              // Format the date in the right format.
              //
              switch ( data.dateFormatChoices[i] ) {
              // Pass the data along in the format chosen by the user OR in binary format...
              //
                case PGBulkLoaderMeta.NR_DATE_MASK_PASS_THROUGH:
                  if ( valueMeta.isStorageBinaryString() ) {
                    pgCopyOut.write( (byte[]) valueData);
                  } else {
                    String dateString = valueMeta.getString( valueData );
                    if ( dateString != null ) {
                      pgCopyOut.write( dateString.getBytes());
                    }
                  }
                  break;

                // Convert to a "YYYY-MM-DD" format
                //
                case PGBulkLoaderMeta.NR_DATE_MASK_DATE:
                  String dateString = data.dateMeta.getString( valueMeta.getDate( valueData ) );
                  if ( dateString != null ) {
                    pgCopyOut.write( dateString.getBytes() );
                  }
                  break;

                // Convert to a "YYYY-MM-DD HH:MM:SS.mmm" format
                //
                case PGBulkLoaderMeta.NR_DATE_MASK_DATETIME:
                  String dateTimeString = data.dateTimeMeta.getString( valueMeta.getDate( valueData ) );
                  if ( dateTimeString != null ) {
                    pgCopyOut.write( dateTimeString.getBytes() );
                  }
                  break;

                default:
                  throw new KettleException ( "PGBulkLoader doesn't know how to handle timestamp (neither passthrough, nor date or datetime for field " + valueMeta.getName() );
              }
              break;
             case ValueMetaInterface.TYPE_BOOLEAN:
              if ( valueMeta.isStorageBinaryString() ) {
                pgCopyOut.write( (byte[]) valueData );
              } else {
                pgCopyOut.write( Double.toString( valueMeta.getNumber( valueData ) ).getBytes() );
              }
              break;
            case ValueMetaInterface.TYPE_NUMBER:
              if ( valueMeta.isStorageBinaryString() ) {
                pgCopyOut.write( (byte[]) valueData );
              } else {
                pgCopyOut.write( Double.toString( valueMeta.getNumber( valueData ) ).getBytes() );
              }
              break;
            case ValueMetaInterface.TYPE_BIGNUMBER:
              if ( valueMeta.isStorageBinaryString() ) {
                pgCopyOut.write( (byte[]) valueData );
              } else {
                BigDecimal big = valueMeta.getBigNumber( valueData );
                if ( big != null ) {
                  pgCopyOut.write( big.toString().getBytes() );
                }
              }
              break;
            default:
              throw new KettleException ( "PGBulkLoader doesn't handle the type " + valueMeta.getTypeDesc() );
          }
        }
      }

      // Now write a newline
      //
      pgCopyOut.write( data.newline );
    } catch ( Exception e ) {
      throw new KettleException( "Error serializing rows of data to the COPY command", e );
    }

  }

  public boolean init( StepMetaInterface smi, StepDataInterface sdi ) {
    meta = (PGBulkLoaderMeta) smi;
    data = (PGBulkLoaderData) sdi;

    String enclosure = environmentSubstitute( meta.getEnclosure() );
    String separator = environmentSubstitute( meta.getDelimiter() );

    if ( super.init( smi, sdi ) ) {
      try {
        data.db = new Database( this, meta.getDatabaseMeta() );
        if ( enclosure != null ) {
          data.quote = enclosure.getBytes();
        } else {
          data.quote = new byte[] {};
        }
        if ( separator != null ) {
          data.separator = separator.getBytes();
        } else {
          data.separator = new byte[] {};
        }
        data.newline = Const.CR.getBytes();
  
        data.dateFormatChoices = new int[meta.getFieldStream().length];
        for ( int i = 0; i < data.dateFormatChoices.length; i++ ) {
          if ( Const.isEmpty( meta.getDateMask()[i] ) ) {
            data.dateFormatChoices[i] = PGBulkLoaderMeta.NR_DATE_MASK_PASS_THROUGH;
          } else if ( meta.getDateMask()[i].equalsIgnoreCase( PGBulkLoaderMeta.DATE_MASK_DATE ) ) {
            data.dateFormatChoices[i] = PGBulkLoaderMeta.NR_DATE_MASK_DATE;
          } else if ( meta.getDateMask()[i].equalsIgnoreCase( PGBulkLoaderMeta.DATE_MASK_DATETIME ) ) {
            data.dateFormatChoices[i] = PGBulkLoaderMeta.NR_DATE_MASK_DATETIME;
          } else { // The default : just pass it along...
            data.dateFormatChoices[i] = PGBulkLoaderMeta.NR_DATE_MASK_PASS_THROUGH;
          }
  
        }
        // Connect to the database (unless using pooled connections)
        if ( getTransMeta().isUsingUniqueConnections() ) {
          synchronized ( getTrans() ) {
            data.db.connect( getTrans().getTransactionId(), getPartitionID() );
          }
        } else {
          data.db.connect( getPartitionID() );
        }
        // Better do the truncate and copy in the same transaction if possible, it will avoid journalling altogether in some cases
        data.db.setAutoCommit(false);
        // Do the truncate if necessary. Only do it on copy 0
        // FIXME: there is another possible optimization: BEGIN;TRUNCATE TABLE; COPY INTO…
        // generates no PG journal if in one single transaction
        String loadAction = environmentSubstitute( meta.getLoadAction() );
        if ( loadAction.equalsIgnoreCase( "truncate" )
         && ( ( getCopy() == 0 && getUniqueStepNrAcrossSlaves() == 0 ) || !Const.isEmpty( getPartitionID() ) )  ) {
            logDetailed("Truncating TABLE " + environmentSubstitute( meta.getTableName() ));
            data.db.truncateTable( environmentSubstitute( meta.getSchemaName() ), environmentSubstitute( meta.getTableName() ) );
            // If there is only one copy, we keep the transaction open, to reduce journalling in PG if possible
            if (getStepMeta().getCopies() > 1) {
                // Need to start a new transaction, or our transaction will keep its exclusive lock on the table, and hang everything
                data.db.commit();
            }
        }
  
        return true;
      } catch ( KettleException e ) {
        logError( "An error occurred intialising this step: " + e.getMessage() );
        stopAll();
        setErrors( 1 );
      }
    }
    return false;
  }

}
