// jTDS JDBC Driver for Microsoft SQL Server and Sybase
// Copyright (C) 2004 The jTDS Project
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package net.sourceforge.jtds.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * @author
 *    Holger Rehn
 */
public class StatementTest extends TestBase
{

   public StatementTest( String name )
   {
      super( name );
   }

   /**
    * Test for bug #559, unique constraint violation error hidden by an internal
    * jTDS error.
    */
   public void testBug559()
      throws Exception
   {
      Statement st = con.createStatement();
      st.executeUpdate( "create table #Bug559 (A int, unique (A))" );

      try
      {
         st.executeUpdate( "select 1;insert into #Bug559 values( 1 );insert into #Bug559 values( 1 )" );
         fail();
      }
      catch( SQLException e )
      {
         // expected, executeUpdate() cannot return a resultset
         assertTrue( e.getMessage().toLowerCase().contains( "executeupdate" ) );
      }

      st.close();
   }

   /**
    * Test for bug #609, slow finalization in {@link SharedSocket#closeStream()}
    * can block JVM finalizer thread or cause OOM errors.
    */
   public void testBug609()
      throws Exception
   {
      final int        STATEMENTS = 50000;
      final int        THREADS    = 10;
      final Connection connection = con;
      final boolean[]  running    = new boolean[] { true };

      List block = new ArrayList( 1000 );

      try
      {
         while( true )
         {
            block.add( new byte[32*1024*1024] );
            System.gc();
         }
      }
      catch( OutOfMemoryError oome )
      {
         block.remove( block.size() - 1 );
      }

      System.gc();
      System.out.println( "free memory: " + Runtime.getRuntime().freeMemory() / 1024 / 1024 + " MB" );

      Statement sta = connection.createStatement();
      sta.executeUpdate( "create table #bug609( A int primary key, B varchar(max) )" );
      sta.close();

      Thread[] threads = new Thread[THREADS];

      // start threads that keeps sending data to block VirtualSocket table in SharedSocket as much as possible
      for( int t = 0; t < threads.length; t ++ )
      {
         final int i = t;
         threads[t] = new Thread()
         {
            public void run()
            {
               try
               {
                  Statement sta = connection.createStatement();
                  sta.executeUpdate( "insert into #bug609 values( " + i + ", 'nix' )" );

                  String value = "BIGVAL";
                  while( value.length() < 64 * 1024 )
                  {
                     value += value + "BIGVAL";
                  }

                  String sql = "update #bug609 set B = '" + value + "' where A = " + i;

                  while( running[0] )
                  {
                     sta.executeUpdate( sql );
                  }

                  sta.close();
               }
               catch( SQLException s )
               {
                  // test stopped, connection is closed
               }
               catch( Throwable t )
               {
                  t.printStackTrace();
               }
            }
         };

         threads[t].setPriority( Thread.MIN_PRIORITY );
         threads[t].start();
      }

      int  stats = 0;
      long start = System.currentTimeMillis();

      try
      {
         // buffer some statements that can later be closed together, otherwise
         // the connection's TdsCore cache would prevent the TdsCore from being
         // closed (and SharedSocket.closeStream to be called) most of the time
         Statement[] buffered = new Statement[2500];

         for( ; stats < STATEMENTS; stats ++ )
         {
            int r = stats % buffered.length;

            buffered[r] = con.createStatement();

            if( r == buffered.length - 1 )
            {
               for( int c = 0; c < buffered.length; c ++ )
               {
                  buffered[c] = null;
               }

               System.out.println( stats + 1 );
            }
         }
      }
      catch( OutOfMemoryError oome )
      {
         block = null;
         System.gc();
         fail( "OOM after " + (System.currentTimeMillis() - start) + " ms, " + stats + " statements created successfully" );
      }

      long elapsed = System.currentTimeMillis() - start;
      System.out.println( "time: " + elapsed + " ms" );

      assertTrue( elapsed < 10000 );

      // stop threads
      running[0] = false;

      for( int t = 0; t < threads.length; t ++ )
      {
         threads[t].join();
      }
   }

   /**
    * Test for bug #473, Statement.setMaxRows() also effects INSERT, UPDATE,
    * DELETE and SELECT INTO.
    */
   public void testBug473()
      throws Exception
   {
      Statement sta = con.createStatement();

      // create test table and fill with data
      sta.executeUpdate( "create table #Bug473( X int )" );
      sta.executeUpdate( "insert into #Bug473 values( 1 )" );
      sta.executeUpdate( "insert into #Bug473 values( 2 )" );

      // copy all data (maxRows shouldn't have any effect)
      sta.setMaxRows( 1 );
      sta.executeUpdate( "select * into #copy from #Bug473" );

      // ensure all table data has been copied
      sta.setMaxRows( 0 );
      ResultSet res = sta.executeQuery( "select * from #copy" );
      assertTrue ( res.next() );
      assertTrue ( res.next() );
      assertFalse( res.next() );

      res.close();
      sta.close();
   }

   /**
    * Test for bug #635, select from a view with order by clause doesn't work if
    * correctly if using Statement.setMaxRows().
    */
   public void testBug635()
      throws Exception
   {
      final int[] data = new int[] { 1, 3, 5, 7, 9, 2, 4, 6, 8, 10 };

      dropTable( "Bug635T" );
      dropView ( "Bug635V" );

      Statement sta = con.createStatement();
      sta.setMaxRows( 7 );

      sta.executeUpdate( "create table Bug635T( X int )" );
      sta.executeUpdate( "create view Bug635V as select * from Bug635T" );

      for( int i = 0; i < data.length; i ++ )
      {
         sta.executeUpdate( "insert into Bug635T values( " + data[i] + " )" );
      }

      ResultSet res = sta.executeQuery( "select X from Bug635V order by X" );

      for( int i = 1; i <= 7; i ++ )
      {
         assertTrue( res.next() );
         assertEquals( i, res.getInt( 1 ) );
      }

      res.close();
      sta.close();
   }

   /**
    * Test for bug #624, full text search causes connection reset when connected
    * to Microsoft SQL Server 2008.
    */
   // TODO: test CONTAINSTABLE, FREETEXT, FREETEXTTABLE
   public void testFullTextSearch()
      throws Exception
   {
      // cleanup
      dropTable( "Bug624" );
      dropDatabase( "Bug624DB" );

      // create DB
      Statement stmt = con.createStatement();
      stmt.executeUpdate( "create database Bug624DB" );
      stmt.executeUpdate( "use Bug624DB" );

      // create table and fulltext index
      stmt.executeUpdate( "create fulltext catalog FTS_C as default" );
      stmt.executeUpdate( "create table Bug624 ( ID int primary key, A varchar( 100 ) )" );
      ResultSet res = stmt.executeQuery( "select name from sysindexes where object_id( 'Bug624' ) = id" );
      assertTrue( res.next() );
      String pk = res.getString( 1 );
      assertFalse( res.next() );
      res.close();
      stmt.executeUpdate( "create fulltext index on Bug624( A ) key index " + pk );

      // insert test data
      assertEquals( 1, stmt.executeUpdate( "insert into Bug624 values( 0, 'Strange Axolotl, that!' )" ) );

      // wait for the index to be build
      for( boolean indexed = false; ! indexed; )
      {
         res = stmt.executeQuery( "select FULLTEXTCATALOGPROPERTY( 'FTS_C', 'PopulateStatus' )" );
         assertTrue( res.next() );
         indexed = res.getInt( 1 ) == 0;
         res.close();
         Thread.sleep( 10 );
      }

      // query table using CONTAINS
      PreparedStatement ps = con.prepareStatement( "select * from Bug624 where contains( A, ? )" );
      ps.setString( 1, "Axolotl" );
      res = ps.executeQuery();
      assertTrue( res.next() );
      assertEquals( 0, res.getInt( 1 ) );
      assertEquals( "Strange Axolotl, that!", res.getString( 2 ) );
   }

   /**
    * Test for computed results, bug #678.
    */
   public void testComputeClause()
      throws Exception
   {
      final int VALUES = 150;

      Statement sta = con.createStatement();

      sta.executeUpdate( "create table #Bug678( X int, A varchar(10), B int, C bigint )" );

      for( int i = 0; i < VALUES; i ++ )
      {
         sta.executeUpdate( "insert into #Bug678 values( " + i % Math.max( 1, i / 20 ) + ", 'VAL" + i + "'," + ( VALUES - i ) + ", " + (long)i * Integer.MAX_VALUE + " )" );
      }

      assertTrue( sta.execute( "select * from #Bug678 order by X, A asc compute min( A ), max( A ), min( C ), max( C ), avg( B ), sum( B ), count( A ), count_big( C ) by X" ) );

      do
      {
         dump( sta.getResultSet() );
      }
      while( sta.getMoreResults() );

      // no update count expected
      assertEquals( -1, sta.getUpdateCount() );
      sta.close();
   }

   /**
    * <p> Test to ensure that single results generated as result of aggregation
    * operations (COMPUTE clause) can be closed individually without affecting
    * remaining {@link ResultSet}s. </p>
    */
   public void testCloseComputedResult()
      throws Exception
   {
      Statement sta = con.createStatement();

      sta.executeUpdate( "create table #Bug678( NAME varchar(10), CREDITS int )" );

      sta.executeUpdate( "insert into #Bug678 values( 'Alf'  , 10 )" );
      sta.executeUpdate( "insert into #Bug678 values( 'Alf'  , 20 )" );
      sta.executeUpdate( "insert into #Bug678 values( 'Alf'  , 30 )" );
      sta.executeUpdate( "insert into #Bug678 values( 'Ronny',  5 )" );
      sta.executeUpdate( "insert into #Bug678 values( 'Ronny', 10 )" );

      assertTrue( sta.execute( "select * from #Bug678 order by NAME compute sum( CREDITS ) by NAME" ) );

      ResultSet res = sta.getResultSet();

      // check 1st row of 1st ResultSet
      assertTrue  ( res.next() );
      assertEquals( "Alf", res.getString( 1 ) );
      assertEquals( 10, res.getInt( 2 ) );
      assertTrue  ( res.next() );

      // close 1st ResultSet
      res.close();

      // 3 ResultSets should be left
      assertTrue( sta.getMoreResults() );
      res = sta.getResultSet();

      // close 2nd (computed) ResultSet without processing it
      res.close();

      // 2 ResultSets should be left
      assertTrue( sta.getMoreResults() );
      res = sta.getResultSet();

      // check 1st row of 3rd ResultSet
      assertTrue( res.next() );
      assertEquals( "Ronny", res.getString( 1 ) );
      assertEquals( 5, res.getInt( 2 ) );

      // close 3rd ResultSet
      res.close();

      // 1 ResultSet should be left
      assertTrue( sta.getMoreResults() );
      res = sta.getResultSet();

      // check 1st row of 4th (computed) ResultSet
      assertTrue( res.next() );
      assertEquals( 15, res.getInt( 1 ) );
      assertFalse( res.next() );

      // no ResultSets should be left
      assertFalse( sta.getMoreResults() );

      sta.close();
   }

   /**
    *
    */
   public void testConcurrentClose()
      throws Exception
   {
      final int THREADS    =  10;
      final int STATEMENTS = 200;
      final int RESULTSETS = 100;

      final List errors = new ArrayList();

      final Statement[] stm = new Statement[STATEMENTS];
      final ResultSet[] res = new ResultSet[STATEMENTS*RESULTSETS];

      Connection con = getConnection();

      for( int i = 0; i < STATEMENTS; i ++ )
      {
         stm[i] = con.createStatement();

         for( int r = 0; r < RESULTSETS; r ++ )
         {
            res[i * RESULTSETS + r] = stm[i].executeQuery( "select 1" );
         }
      }

      Thread[] threads = new Thread[THREADS];

      for( int i = 0; i < THREADS; i ++ )
      {
         threads[i] = new Thread( "closer " + i )
         {
            public void run()
            {
               try
               {
                  for( int i = 0; i < STATEMENTS; i ++ )
                  {
                     stm[i].close();
                  }
               }
               catch( Exception e )
               {
                  synchronized( errors )
                  {
                     errors.add( e );
                  }
               }
            }
         };
      }

      for( int i = 0; i < THREADS; i ++ )
      {
         threads[i].start();
      }

      for( int i = 0; i < THREADS; i ++ )
      {
         threads[i].join();
      }

      for( int i = 0; i < errors.size(); i ++ )
      {
         ( (Exception) errors.get( i ) ).printStackTrace();
      }

      assertTrue( errors.toString(), errors.isEmpty() );
   }

   /**
    * Regression test for bug #677, deadlock in {@link JtdsStatement#close()}.
    */
   public void testCloseDeadlock()
      throws Exception
   {
      final int THREADS    =  100;
      final int STATEMENTS = 1000;

      final List errors = new ArrayList();

      Thread[] threads = new Thread[THREADS];

      for( int i = 0; i < THREADS; i ++ )
      {
         threads[i] = new Thread( "deadlock " + i )
         {
            public void run()
            {
               try
               {
                  Connection con = getConnection();
                  final Statement[] stm = new Statement[STATEMENTS];

                  for( int i = 0; i < STATEMENTS; i ++ )
                  {
                     stm[i] = con.createStatement();
                  }

                  new Thread( Thread.currentThread().getName() + " (closer)" )
                  {
                     public void run()
                     {
                        try
                        {
                           for( int i = 0; i < STATEMENTS; i ++ )
                           {
                              stm[i].close();
                           }
                        }
                        catch( SQLException e )
                        {
                           // statements might already be closed by closing the connection
                           if( ! "HY010".equals( e.getSQLState() ) )
                           {
                              synchronized( errors )
                              {
                                 errors.add( e );
                              }
                           }
                        }
                     }
                  }.start();

                  Thread.sleep( 1 );
                  con.close();
               }
               catch( Exception e )
               {
                  synchronized( errors )
                  {
                     errors.add( e );
                  }
               }
            }
         };
      }

      for( int i = 0; i < THREADS; i ++ )
      {
         threads[i].start();
      }

      System.currentTimeMillis();
      int  running = THREADS;

      while( running != 0 )
      {
         Thread.sleep( 2500 );

         int last = running;
         running  = THREADS;

         for( int i = 0; i < THREADS; i ++ )
         {
            if( threads[i].getState() == Thread.State.TERMINATED )
            {
               running --;
            }
         }

         if( running == last )
         {
//             for( int i = 0; i < THREADS; i ++ )
//             {
//                if( threads[i].getState() != Thread.State.TERMINATED )
//                {
//                   Exception e = new Exception();
//                   e.setStackTrace( threads[i].getStackTrace() );
//                   e.printStackTrace();
//                }
//             }

            fail( "deadlock detected, none of the remaining connections closed within 2500 ms" );
         }
      }

//      for( int i = 0; i < errors.size(); i ++ )
//      {
//         ( (Exception) errors.get( i ) ).printStackTrace();
//      }

      assertTrue( errors.toString(), errors.isEmpty() );
   }

    /**
     * Test for #676, error in multi line comment handling.
     */
    public void testMultiLineComment()
       throws Exception
    {
       Statement st = con.createStatement();

       st.executeUpdate( "create table /*/ comment '\"?@[*-} /**/*/ #Bug676a (A int) /* */" );

       try
       {
          // SQL server stacks, instead of ignoring 'inner comments'
          st.executeUpdate( "create table /* /* */ #Bug676b (A int)" );
       }
       catch( SQLException e )
       {
          // thrown by jTDS due to unclosed 'inner comment'
          assertEquals( String.valueOf( 22025 ), e.getSQLState() );
       }

       st.close();
    }

   /**
    * Test for bug #669, no error if violating unique constraint in update.
    */
   public void testDuplicateKey()
      throws Exception
   {
      Statement st = con.createStatement();
      st.executeUpdate( "create table #Bug669 (A int, unique (A))" );
      st.executeUpdate( "insert into #Bug669 values( 1 )" );
      try
      {
         st.executeUpdate( "insert into #Bug669 values( 1 )" );
         fail();
      }
      catch( SQLException e )
      {
         // expected, unique constraint violation
      }
      try
      {
         st.execute( "insert into #Bug669 values( 1 )" );
         fail();
      }
      catch( SQLException e )
      {
         // expected, unique constraint violation
      }
      st.close();
   }

   /**
    * <p> Test for bug [1694194], queryTimeout does not work on MSSQL2005 when
    * property 'useCursors' is set to 'true'. Furthermore, the test also checks
    * timeout with a query that cannot use a cursor. </p>
    *
    * <p> This test requires property 'queryTimeout' to be set to true. </p>
    */
   public void testQueryTimeout() throws Exception
   {
      Statement st = con.createStatement();
      st.setQueryTimeout( 1 );

      st.execute( "create procedure #testTimeout as begin waitfor delay '00:00:30'; select 1; end" );

      long start = System.currentTimeMillis();

      try
      {
         // this query doesn't use a cursor
         st.executeQuery( "exec #testTimeout" );
         fail( "query did not time out" );
      }
      catch( SQLException e )
      {
         assertEquals( "HYT00", e.getSQLState() );
         assertEquals( 1000, System.currentTimeMillis() - start, 50 );
      }

      st.execute( "create table #dummy1(A varchar(200))" );
      st.execute( "create table #dummy2(B varchar(200))" );
      st.execute( "create table #dummy3(C varchar(200))" );

      // create test data
      con.setAutoCommit( false );
      for( int i = 0; i < 100; i++ )
      {
         st.execute( "insert into #dummy1 values('" + i + "')" );
         st.execute( "insert into #dummy2 values('" + i + "')" );
         st.execute( "insert into #dummy3 values('" + i + "')" );
      }
      con.commit();
      con.setAutoCommit( true );

      start = System.currentTimeMillis();
      try
      {
         // this query can use a cursor
         st.executeQuery( "select * from #dummy1, #dummy2, #dummy3 order by A desc, B asc, C desc" );
         fail( "query did not time out" );
      }
      catch( SQLException e )
      {
         assertEquals( "HYT00", e.getSQLState() );
         assertEquals( 1000, System.currentTimeMillis() - start, 100 );
      }

      st.close();
   }

}